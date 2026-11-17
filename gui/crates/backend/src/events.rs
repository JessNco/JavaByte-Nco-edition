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

    /// Positions of all events matching the filter, computed once per filter.
    fn matches(&mut self, filter: &EventFilter) -> &[u32] {
        let stale = self.cached.as_ref().is_none_or(|(cached, _)| cached != filter);
        if stale {
            let needle = filter.text.as_deref().map(str::to_lowercase).filter(|text| !text.is_empty());
            let category = filter.category.as_deref().filter(|c| !c.is_empty());
            let mut found = Vec::new();
            for index in 0..self.source.len() {
                if category.is_some_and(|wanted| self.source.category(index) != wanted) {
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
    filter.category.as_deref().is_none_or(str::is_empty) && filter.text.as_deref().is_none_or(str::is_empty)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::synthetic::SyntheticEvents;
    use std::time::Instant;

    fn store(rows: u64) -> EventStore {
        EventStore::new(Box::new(SyntheticEvents::new(rows)))
    }

    fn request(offset: u64, limit: u32, category: Option<&str>, text: Option<&str>) -> PageRequest {
        PageRequest {
            offset,
            limit,
            filter: EventFilter { category: category.map(String::from), text: text.map(String::from) },
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
