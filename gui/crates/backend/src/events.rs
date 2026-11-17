//! Paging and filtering over a large list of events.

use javabyte_gui_protocol::{EventFilter, EventRow, MAX_PAGE_ROWS, Page, PageRequest};

/// Anything that can produce event rows by position.
///
/// The synthetic source computes rows on demand and stores nothing, which lets the trial
/// exercise 300,000 rows at almost no memory cost; a loaded report stores its rows.
pub trait EventSource {
    fn len(&self) -> u64;
    fn row(&self, index: u64) -> EventRow;
    /// The category of a row, which filtering needs far more often than the whole row.
    fn category(&self, index: u64) -> &str;
}

/// Serves pages of events, remembering the most recent filter result so that scrolling a
/// filtered list does not rescan every event for every page.
pub struct EventStore {
    source: Box<dyn EventSource + Send + Sync>,
    cached: Option<(EventFilter, Vec<u32>)>,
}

impl EventStore {
    pub fn new(source: Box<dyn EventSource + Send + Sync>) -> Self {
        Self { source, cached: None }
    }

    pub fn len(&self) -> u64 {
        self.source.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Returns the requested slice. Out-of-range offsets give an empty page, never an error:
    /// a screen that scrolled while the filter changed should simply see nothing there.
    pub fn page(&mut self, request: &PageRequest) -> Page {
        let limit = u64::from(request.limit.min(MAX_PAGE_ROWS));
        let unfiltered_total = self.source.len();
        if is_empty_filter(&request.filter) {
            let end = (request.offset + limit).min(unfiltered_total);
            let rows = (request.offset.min(end)..end).map(|i| self.source.row(i)).collect();
            return Page { total: unfiltered_total, unfiltered_total, offset: request.offset, rows };
        }
        let matches = self.matches(&request.filter);
        let total = matches.len() as u64;
        let start = request.offset.min(total) as usize;
        let end = (request.offset + limit).min(total) as usize;
        // Copied out (at most MAX_PAGE_ROWS indexes) so the cache borrow ends before rows are built.
        let wanted = matches[start..end].to_vec();
        let rows = wanted.into_iter().map(|i| self.source.row(u64::from(i))).collect();
        Page { total, unfiltered_total, offset: request.offset, rows }
    }

    /// The actions that occur in a category, sorted, for the filter's list.
    pub fn actions(&mut self, category: &str) -> Vec<String> {
        let mut found = std::collections::BTreeSet::new();
        for index in 0..self.source.len() {
            if self.source.category(index) == category {
                found.insert(self.source.row(index).action);
            }
        }
        found.into_iter().collect()
    }

    /// Where the event numbered `seq` is among those matching the filter: its row position, or
    /// `None` if the filter hides it or there is no such event. Event numbers rise with position,
    /// so this is a binary search.
    pub fn position_of(&mut self, seq: u64, filter: &EventFilter) -> Option<u64> {
        if is_empty_filter(filter) {
            let len = self.source.len();
            let (mut low, mut high) = (0, len);
            while low < high {
                let mid = (low + high) / 2;
                match self.source.row(mid).seq.cmp(&seq) {
                    std::cmp::Ordering::Less => low = mid + 1,
                    std::cmp::Ordering::Greater => high = mid,
                    std::cmp::Ordering::Equal => return Some(mid),
                }
            }
            return None;
        }
        let matches = self.matches(filter).to_vec();
        let found = matches.binary_search_by(|&index| self.source.row(u64::from(index)).seq.cmp(&seq)).ok()?;
        Some(found as u64)
    }

    /// Positions of all events matching the filter, computed once per filter.
    fn matches(&mut self, filter: &EventFilter) -> &[u32] {
        let stale = self.cached.as_ref().is_none_or(|(cached, _)| cached != filter);
        if stale {
            let needle = filter.text.as_deref().map(str::to_lowercase).filter(|text| !text.is_empty());
            let category = filter.category.as_deref().filter(|c| !c.is_empty());
            let action = filter.action.as_deref().filter(|a| !a.is_empty());
            let mut found = Vec::new();
            for index in 0..self.source.len() {
                if category.is_some_and(|wanted| self.source.category(index) != wanted) {
                    continue;
                }
                if action.is_some_and(|wanted| self.source.row(index).action != wanted) {
                    continue;
                }
                if let Some(needle) = &needle {
                    let row = self.source.row(index);
                    let haystack = format!("{} {} {}", row.action, row.details, row.caller.as_deref().unwrap_or(""));
                    if !haystack.to_lowercase().contains(needle.as_str()) {
                        continue;
                    }
                }
                // u32 is enough: the emulator caps each category at far fewer events.
                found.push(index as u32);
            }
            self.cached = Some((filter.clone(), found));
        }
        &self.cached.as_ref().expect("filled above").1
    }
}

fn is_empty_filter(filter: &EventFilter) -> bool {
    filter.category.as_deref().is_none_or(str::is_empty)
        && filter.action.as_deref().is_none_or(str::is_empty)
        && filter.text.as_deref().is_none_or(str::is_empty)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn actions_and_positions() {
        let mut events = store(10_000);
        let actions = events.actions("net");
        assert!(!actions.is_empty() && actions.windows(2).all(|w| w[0] < w[1]), "{actions:?}");
        let all = EventFilter::default();
        assert_eq!(events.position_of(1234, &all), Some(1234));
        assert_eq!(events.position_of(99_999, &all), None);
        let net = EventFilter { category: Some("net".into()), action: Some(actions[0].clone()), text: None };
        let page = events.page(&PageRequest { offset: 3, limit: 1, filter: net.clone() });
        let seq = page.rows[0].seq;
        assert!(page.rows.iter().all(|r| r.category == "net" && r.action == actions[0]));
        assert_eq!(events.position_of(seq, &net), Some(3), "found within the filtered list");
        assert_eq!(events.position_of(seq + 1, &net).map(|_| ()), events.page(&PageRequest { offset: 0, limit: 500, filter: net.clone() }).rows.iter().find(|r| r.seq == seq + 1).map(|_| ()));
    }
    use crate::synthetic::SyntheticEvents;
    use std::time::Instant;

    fn store(rows: u64) -> EventStore {
        EventStore::new(Box::new(SyntheticEvents::new(rows)))
    }

    fn request(offset: u64, limit: u32, category: Option<&str>, text: Option<&str>) -> PageRequest {
        PageRequest {
            offset,
            limit,
            filter: EventFilter { category: category.map(String::from), action: None, text: text.map(String::from) },
        }
    }

    #[test]
    fn pages_cover_the_list_without_gaps() {
        let mut events = store(1_000);
        let first = events.page(&request(0, 100, None, None));
        let last = events.page(&request(950, 100, None, None));
        assert_eq!(first.rows.len(), 100);
        assert_eq!(first.rows[99].seq, 99);
        assert_eq!(last.rows.len(), 50);
        assert_eq!(last.rows[49].seq, 999);
        assert!(events.page(&request(5_000, 100, None, None)).rows.is_empty());
    }

    #[test]
    fn page_size_is_capped() {
        assert_eq!(store(10_000).page(&request(0, 100_000, None, None)).rows.len(), MAX_PAGE_ROWS as usize);
    }

    #[test]
    fn filters_combine_and_report_both_totals() {
        let mut events = store(20_000);
        let net = events.page(&request(0, 500, Some("net"), None));
        assert!(net.total > 0 && net.total < 20_000);
        assert_eq!(net.unfiltered_total, 20_000);
        assert!(net.rows.iter().all(|row| row.category == "net"));
        let connect = events.page(&request(0, 500, Some("net"), Some("CONNECT")));
        assert!(connect.total > 0 && connect.total < net.total);
        assert!(connect.rows.iter().all(|row| row.action == "connect"));
    }

    /// The trial's grid must stay smooth at 300,000 rows. Scrolling asks for a page at a time;
    /// a new filter scans everything once.
    #[test]
    fn large_lists_stay_fast() {
        let mut events = store(300_000);
        let started = Instant::now();
        for offset in (0..300_000).step_by(30_000) {
            assert_eq!(events.page(&request(offset, 60, None, None)).rows.len(), 60);
        }
        let paging = started.elapsed();
        let started = Instant::now();
        let filtered = events.page(&request(0, 60, None, Some("duckdns")));
        let first_filter = started.elapsed();
        let started = Instant::now();
        events.page(&request(filtered.total / 2, 60, None, Some("duckdns")));
        let cached = started.elapsed();
        // Generous limits so slow CI machines pass; the point is the order of magnitude.
        assert!(paging.as_millis() < 100, "paging took {paging:?}");
        assert!(first_filter.as_millis() < 2_000, "first filter took {first_filter:?}");
        assert!(cached.as_millis() < 20, "cached filter took {cached:?}");
    }
}
