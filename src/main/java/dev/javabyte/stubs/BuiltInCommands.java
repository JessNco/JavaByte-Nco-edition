package dev.javabyte.stubs;

import dev.javabyte.env.MachineProfile;
import dev.javabyte.vm.VirtualMachine;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Output for system commands a sample runs to learn about its machine, when the config has no
 * {@code processes} rule for them.
 *
 * <p>Reconnaissance is a stock part of Java malware: {@code whoami} and {@code hostname} for
 * the victim's name, {@code tasklist} to look for analysis tools (and to count processes,
 * since sandboxes run few), {@code wmic} for the maker, model, BIOS serial and disk size,
 * {@code systeminfo}, {@code ipconfig} and {@code getmac} for the rest. A real machine never
 * answers these with nothing, and an empty answer is itself a sign of a sandbox. So they are
 * answered here, from the {@link MachineProfile}, in the layout the real programs use, so that
 * parsers written against real output work.
 *
 * <p>Commands may be wrapped in {@code cmd /c} and piped through {@code find} or
 * {@code findstr}. Anything else (redirection, chains with {@code &&}, other programs) is not
 * recognised, and the caller falls back to empty output.
 */
final class BuiltInCommands {

    private BuiltInCommands() {}

    /**
     * What a built-in command printed.
     *
     * @param command the program that answered, for the {@code matchedRule} of the event
     */
    record Answer(String command, String stdout, String stderr, int exitCode) {}

    /** Line ending of Windows console programs. */
    private static final String CRLF = "\r\n";
    /** WMIC ends each line with an extra carriage return when its output is redirected. */
    private static final String WMIC_EOL = "\r\r\n";

    private static final Pattern SHELL = Pattern.compile(
            "(?i)^\"?(?:[a-z]:\\\\windows\\\\system32\\\\)?cmd(?:\\.exe)?\"?\\s+/[ck]\\s+(.*)$");

    /**
     * Answers a command line, or returns null if it is not one of the built-in commands.
     *
     * @param ownPid the process id given to this launch, which {@code tasklist} lists
     */
    static Answer answer(VirtualMachine vm, String line, long ownPid) {
        String inner = line.trim();
        Matcher shell = SHELL.matcher(inner);
        if (shell.matches()) {
            inner = shell.group(1).trim();
            if (inner.length() > 1 && inner.startsWith("\"") && inner.endsWith("\"")) {
                inner = inner.substring(1, inner.length() - 1).trim();
            }
        }
        if (inner.isEmpty() || inner.matches(".*[<>&].*")) {
            return null;
        }
        String[] stages = inner.split("\\|");
        Answer answer = run(vm, tokens(stages[0]), ownPid);
        for (int i = 1; i < stages.length && answer != null; i++) {
            answer = filter(answer, tokens(stages[i]));
        }
        return answer;
    }

    /** One command without pipes. */
    private static Answer run(VirtualMachine vm, List<String> argv, long ownPid) {
        if (argv.isEmpty()) {
            return null;
        }
        MachineProfile machine = vm.env.machine;
        String program = program(argv.get(0));
        List<String> args = argv.subList(1, argv.size());
        String flags = String.join(" ", args).toLowerCase(Locale.ROOT);
        if (!machine.windows) {
            return switch (program) {
                case "whoami" -> ok(program, machine.userName + "\n");
                case "hostname" -> ok(program, machine.hostName + "\n");
                case "nproc" -> ok(program, machine.processors + "\n");
                case "id" -> ok(program, "uid=1000(" + machine.userName + ") gid=1000(" + machine.userName + ") groups=1000("
                        + machine.userName + "),4(adm),24(cdrom),27(sudo),30(dip),46(plugdev)\n");
                case "uname" -> ok(program, uname(vm, flags) + "\n");
                default -> null;
            };
        }
        return switch (program) {
            case "whoami" -> ok(program, flags.contains("/user") || flags.contains("/all") ? whoamiUser(machine)
                    : machine.qualifiedUser() + CRLF);
            case "hostname" -> ok(program, machine.hostName + CRLF);
            case "ver" -> ok(program, CRLF + "Microsoft Windows [Version " + MachineProfile.OS_FULL_VERSION + "]" + CRLF);
            case "echo" -> ok(program, expand(vm, String.join(" ", args)) + CRLF);
            case "tasklist" -> tasklist(machine, args, ownPid);
            case "wmic" -> wmic(vm, args);
            case "systeminfo" -> ok(program, systeminfo(vm));
            case "ipconfig" -> ok(program, ipconfig(machine, flags.contains("/all")));
            case "getmac" -> ok(program, getmac(machine, flags));
            default -> null;
        };
    }

    private static Answer ok(String program, String stdout) {
        return new Answer(program, stdout, "", 0);
    }

    /** {@code C:\Windows\System32\WHOAMI.EXE} and {@code whoami} are the same program. */
    private static String program(String token) {
        String name = token.substring(Math.max(token.lastIndexOf('\\'), token.lastIndexOf('/')) + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    /** Splits a command line into words, keeping quoted parts together and dropping the quotes. */
    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : text.trim().toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    any = false;
                }
            } else {
                current.append(c);
                any = true;
            }
        }
        if (any) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    /** {@code %NAME%} replaced from the guest's environment variables, ignoring case, as cmd does. */
    private static String expand(VirtualMachine vm, String text) {
        Matcher reference = Pattern.compile("%([^%]+)%").matcher(text);
        StringBuilder out = new StringBuilder();
        while (reference.find()) {
            String value = vm.env.variables.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(reference.group(1)))
                    .map(Map.Entry::getValue).findFirst().orElse(reference.group());
            reference.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        return reference.appendTail(out).toString();
    }

    // ---------------------------------------------------------------- find and findstr

    /** Applies a {@code find} or {@code findstr} stage; null for any other program. */
    private static Answer filter(Answer input, List<String> argv) {
        if (argv.isEmpty()) {
            return null;
        }
        String program = program(argv.get(0));
        if (!program.equals("find") && !program.equals("findstr")) {
            return null;
        }
        boolean ignoreCase = false;
        boolean invert = false;
        List<String> patterns = new ArrayList<>();
        for (String arg : argv.subList(1, argv.size())) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.equals("/i")) {
                ignoreCase = true;
            } else if (lower.equals("/v")) {
                invert = true;
            } else if (lower.startsWith("/c:")) {
                patterns.add(arg.substring(3));
            } else if (!arg.startsWith("/")) {
                // findstr treats the words of an unquoted or quoted pattern as alternatives.
                patterns.addAll(program.equals("findstr") ? List.of(arg.split("\\s+")) : List.of(arg));
            }
        }
        boolean fold = ignoreCase;
        Predicate<String> matches = text -> patterns.stream().anyMatch(p -> fold
                ? text.toLowerCase(Locale.ROOT).contains(p.toLowerCase(Locale.ROOT)) : text.contains(p));
        StringBuilder out = new StringBuilder();
        for (String line : input.stdout().split("(?<=\n)")) {
            String bare = line.replaceAll("[\r\n]+$", "");
            if (!bare.isEmpty() && matches.test(bare) != invert) {
                out.append(bare).append(CRLF);
            }
        }
        // Both programs exit with 1 when nothing matched.
        return new Answer(input.command() + "|" + program, out.toString(), input.stderr(), out.length() == 0 ? 1 : 0);
    }

    // ---------------------------------------------------------------- whoami, uname

    private static String whoamiUser(MachineProfile machine) {
        String name = machine.qualifiedUser();
        String sid = machine.userSid();
        int nameWidth = Math.max("User Name".length(), name.length());
        int sidWidth = Math.max("SID".length(), sid.length());
        return CRLF + "USER INFORMATION" + CRLF + "----------------" + CRLF + CRLF
                + pad("User Name", nameWidth) + " " + "SID" + CRLF
                + "=".repeat(nameWidth) + " " + "=".repeat(sidWidth) + CRLF
                + pad(name, nameWidth) + " " + sid + CRLF;
    }

    private static String uname(VirtualMachine vm, String flags) {
        MachineProfile machine = vm.env.machine;
        String kernel = vm.env.properties.getProperty("os.version");
        if (flags.contains("-a")) {
            return "Linux " + machine.hostName + " " + kernel + " #101-Ubuntu SMP Tue Nov 14 13:30:08 UTC 2023 x86_64 x86_64 x86_64 GNU/Linux";
        }
        if (flags.contains("-r")) {
            return kernel;
        }
        if (flags.contains("-n")) {
            return machine.hostName;
        }
        if (flags.contains("-m")) {
            return "x86_64";
        }
        return "Linux";
    }

    // ---------------------------------------------------------------- tasklist

    private static Answer tasklist(MachineProfile machine, List<String> args, long ownPid) {
        String format = "table";
        boolean header = true;
        List<Predicate<MachineProfile.ProcessEntry>> filters = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i).toLowerCase(Locale.ROOT);
            if (arg.equals("/fo") && i + 1 < args.size()) {
                format = args.get(++i).toLowerCase(Locale.ROOT);
            } else if (arg.equals("/nh")) {
                header = false;
            } else if (arg.equals("/fi") && i + 1 < args.size()) {
                Predicate<MachineProfile.ProcessEntry> filter = taskFilter(args.get(++i));
                if (filter != null) {
                    filters.add(filter);
                }
            }
        }
        List<MachineProfile.ProcessEntry> rows = new ArrayList<>(machine.processes());
        // tasklist sees itself running.
        rows.add(new MachineProfile.ProcessEntry("tasklist.exe", ownPid, "Console", 1, 9_876));
        rows.removeIf(row -> !filters.stream().allMatch(f -> f.test(row)));
        if (rows.isEmpty()) {
            return ok("tasklist", "INFO: No tasks are running which match the specified criteria." + CRLF);
        }
        StringBuilder out = new StringBuilder();
        switch (format) {
            case "csv" -> {
                if (header) {
                    out.append("\"Image Name\",\"PID\",\"Session Name\",\"Session#\",\"Mem Usage\"").append(CRLF);
                }
                for (MachineProfile.ProcessEntry row : rows) {
                    out.append('"').append(row.image()).append("\",\"").append(row.pid()).append("\",\"").append(row.sessionName())
                            .append("\",\"").append(row.session()).append("\",\"").append(memory(row)).append('"').append(CRLF);
                }
            }
            case "list" -> {
                for (MachineProfile.ProcessEntry row : rows) {
                    out.append(CRLF).append("Image Name:   ").append(row.image()).append(CRLF)
                            .append("PID:          ").append(row.pid()).append(CRLF)
                            .append("Session Name: ").append(row.sessionName()).append(CRLF)
                            .append("Session#:     ").append(row.session()).append(CRLF)
                            .append("Mem Usage:    ").append(memory(row)).append(CRLF);
                }
            }
            default -> {
                out.append(CRLF);
                if (header) {
                    out.append("Image Name                     PID Session Name        Session#    Mem Usage").append(CRLF)
                            .append("========================= ======== ================ =========== ============").append(CRLF);
                }
                for (MachineProfile.ProcessEntry row : rows) {
                    String image = row.image().length() > 25 ? row.image().substring(0, 25) : row.image();
                    out.append(pad(image, 25)).append(' ').append(padLeft(String.valueOf(row.pid()), 8)).append(' ')
                            .append(pad(row.sessionName(), 16)).append(' ').append(padLeft(String.valueOf(row.session()), 11))
                            .append(' ').append(padLeft(memory(row), 12)).append(CRLF);
                }
            }
        }
        return ok("tasklist", out.toString());
    }

    /** A {@code /fi} filter on the image name or process id; null for filters not modelled, which are ignored. */
    private static Predicate<MachineProfile.ProcessEntry> taskFilter(String expression) {
        Matcher m = Pattern.compile("(?i)\\s*(imagename|pid)\\s+(eq|ne)\\s+(.+?)\\s*").matcher(expression);
        if (!m.matches()) {
            return null;
        }
        String value = m.group(3);
        boolean equal = m.group(2).equalsIgnoreCase("eq");
        String regex = "(?i)" + Pattern.quote(value).replace("*", "\\E.*\\Q");
        Predicate<MachineProfile.ProcessEntry> test = m.group(1).equalsIgnoreCase("pid")
                ? row -> String.valueOf(row.pid()).equals(value) : row -> row.image().matches(regex);
        return equal ? test : test.negate();
    }

    private static String memory(MachineProfile.ProcessEntry row) {
        return String.format(Locale.US, "%,d K", row.memoryKb());
    }

    // ---------------------------------------------------------------- wmic

    /** WMI classes by the names wmic accepts: an alias, or the class name without {@code Win32_}. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("computersystem", "computersystem"), Map.entry("bios", "bios"), Map.entry("baseboard", "baseboard"),
            Map.entry("csproduct", "computersystemproduct"), Map.entry("computersystemproduct", "computersystemproduct"),
            Map.entry("cpu", "processor"), Map.entry("processor", "processor"), Map.entry("diskdrive", "diskdrive"),
            Map.entry("logicaldisk", "logicaldisk"), Map.entry("os", "operatingsystem"),
            Map.entry("operatingsystem", "operatingsystem"), Map.entry("nic", "networkadapter"),
            Map.entry("networkadapter", "networkadapter"), Map.entry("nicconfig", "networkadapterconfiguration"),
            Map.entry("networkadapterconfiguration", "networkadapterconfiguration"),
            Map.entry("desktopmonitor", "desktopmonitor"), Map.entry("videocontroller", "videocontroller"),
            Map.entry("process", "process"), Map.entry("antivirusproduct", "antivirusproduct"));

    private static Answer wmic(VirtualMachine vm, List<String> args) {
        int i = 0;
        while (i < args.size() && args.get(i).startsWith("/")) {
            i++; // /namespace:, /node:, /output: and the like
        }
        if (i >= args.size()) {
            return null;
        }
        String target = args.get(i++).toLowerCase(Locale.ROOT);
        if (target.equals("path") && i < args.size()) {
            target = args.get(i++).toLowerCase(Locale.ROOT).replaceFirst("^win32_", "");
        }
        String wmiClass = ALIASES.get(target);
        if (wmiClass == null) {
            return null;
        }
        String where = null;
        if (i < args.size() && args.get(i).equalsIgnoreCase("where")) {
            StringBuilder clause = new StringBuilder();
            for (i++; i < args.size() && !args.get(i).equalsIgnoreCase("get") && !args.get(i).equalsIgnoreCase("list"); i++) {
                clause.append(args.get(i)).append(' ');
            }
            where = clause.toString().trim();
        }
        if (i >= args.size()) {
            return null;
        }
        String verb = args.get(i++).toLowerCase(Locale.ROOT);
        String format = verb.equals("list") ? "list" : "table";
        List<String> wanted = new ArrayList<>();
        for (; i < args.size(); i++) {
            String arg = args.get(i);
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.equals("/value") || lower.startsWith("/format:list") || lower.startsWith("/format:value")) {
                format = "list";
            } else if (lower.startsWith("/format:csv")) {
                format = "csv";
            } else if (!arg.startsWith("/") && verb.equals("get")) {
                for (String property : arg.split(",")) {
                    if (!property.isBlank()) {
                        wanted.add(property.trim());
                    }
                }
            }
        }
        if (!verb.equals("get") && !verb.equals("list")) {
            return null;
        }
        String host = vm.env.machine.hostName;
        List<Map<String, String>> instances = instances(vm, wmiClass);
        if (where != null) {
            Predicate<Map<String, String>> condition = whereClause(where);
            instances = instances.stream().filter(condition).toList();
        }
        if (instances.isEmpty()) {
            return new Answer("wmic", "", "No Instance(s) Available." + CRLF, 0);
        }
        // Columns come out in alphabetical order whatever order they were asked for in.
        TreeMap<String, String> columns = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, String> sample = instances.get(0);
        if (wanted.isEmpty()) {
            sample.keySet().forEach(key -> columns.put(key, key));
        }
        for (String property : wanted) {
            String canonical = sample.keySet().stream().filter(key -> key.equalsIgnoreCase(property)).findFirst().orElse(null);
            if (canonical == null) {
                return new Answer("wmic", "", "Node - " + host + CRLF + "ERROR:" + CRLF + "Description = Invalid query" + CRLF + CRLF, 44135);
            }
            columns.put(canonical, canonical);
        }
        return ok("wmic", format(host, columns.keySet().stream().toList(), instances, format));
    }

    private static String format(String host, List<String> columns, List<Map<String, String>> instances, String format) {
        StringBuilder out = new StringBuilder();
        switch (format) {
            case "list" -> {
                for (Map<String, String> instance : instances) {
                    out.append(WMIC_EOL).append(WMIC_EOL);
                    for (String column : columns) {
                        out.append(column).append('=').append(instance.get(column)).append(WMIC_EOL);
                    }
                }
                out.append(WMIC_EOL).append(WMIC_EOL);
            }
            case "csv" -> {
                out.append(WMIC_EOL).append("Node,").append(String.join(",", columns)).append(WMIC_EOL);
                for (Map<String, String> instance : instances) {
                    out.append(host);
                    for (String column : columns) {
                        out.append(',').append(instance.get(column));
                    }
                    out.append(WMIC_EOL);
                }
            }
            default -> {
                Map<String, Integer> widths = new LinkedHashMap<>();
                for (String column : columns) {
                    int width = column.length();
                    for (Map<String, String> instance : instances) {
                        width = Math.max(width, instance.get(column).length());
                    }
                    widths.put(column, width + 2);
                }
                for (String column : columns) {
                    out.append(pad(column, widths.get(column)));
                }
                out.append(WMIC_EOL);
                for (Map<String, String> instance : instances) {
                    for (String column : columns) {
                        out.append(pad(instance.get(column), widths.get(column)));
                    }
                    out.append(WMIC_EOL);
                }
                out.append(WMIC_EOL);
            }
        }
        return out.toString();
    }

    /**
     * A {@code where} clause of {@code name='value'} tests joined by {@code and}. Anything more
     * elaborate matches every instance, which for these single-instance classes is the answer
     * a real query would usually give.
     */
    private static Predicate<Map<String, String>> whereClause(String clause) {
        String text = clause.trim();
        if (text.startsWith("(") && text.endsWith(")")) {
            text = text.substring(1, text.length() - 1);
        }
        List<Predicate<Map<String, String>>> tests = new ArrayList<>();
        for (String part : text.split("(?i)\\s+and\\s+")) {
            Matcher m = Pattern.compile("\\s*(\\w+)\\s*=\\s*['\"]?([^'\"]*)['\"]?\\s*").matcher(part);
            if (!m.matches()) {
                return instance -> true;
            }
            String property = m.group(1);
            String value = m.group(2).replace("\\\\", "\\");
            tests.add(instance -> instance.entrySet().stream()
                    .anyMatch(e -> e.getKey().equalsIgnoreCase(property) && e.getValue().equalsIgnoreCase(value)));
        }
        return instance -> tests.stream().allMatch(t -> t.test(instance));
    }

    /** The instances of a WMI class on the simulated machine, each as property name to text. */
    private static List<Map<String, String>> instances(VirtualMachine vm, String wmiClass) {
        MachineProfile machine = vm.env.machine;
        String javaHome = vm.env.properties.getProperty("java.home");
        Map<String, String> one = new LinkedHashMap<>();
        switch (wmiClass) {
            case "computersystem" -> {
                one.put("Caption", machine.hostName);
                one.put("Domain", "WORKGROUP");
                one.put("Manufacturer", machine.manufacturer);
                one.put("Model", machine.model);
                one.put("Name", machine.hostName);
                one.put("NumberOfLogicalProcessors", String.valueOf(machine.processors));
                one.put("NumberOfProcessors", "1");
                one.put("PartOfDomain", "FALSE");
                one.put("PrimaryOwnerName", machine.userName);
                one.put("SystemFamily", machine.model.split(" ")[0]);
                one.put("SystemType", "x64-based PC");
                one.put("TotalPhysicalMemory", String.valueOf(machine.visibleMemoryBytes));
                one.put("UserName", machine.hostName + "\\" + machine.userName);
                one.put("Workgroup", "WORKGROUP");
            }
            case "bios" -> {
                one.put("Manufacturer", machine.manufacturer);
                one.put("Name", MachineProfile.BIOS_VERSION);
                one.put("ReleaseDate", MachineProfile.BIOS_DATE);
                one.put("SerialNumber", machine.serialNumber);
                one.put("SMBIOSBIOSVersion", MachineProfile.BIOS_VERSION);
                // The SMBIOS vendor tag: the maker's first word in capitals, padded to six.
                one.put("Version", pad(machine.manufacturer.split(" ")[0].toUpperCase(Locale.ROOT), 6) + " - 1072009");
            }
            case "baseboard" -> {
                one.put("Manufacturer", machine.manufacturer);
                one.put("Product", "0Y7WYT");
                one.put("SerialNumber", "/" + machine.serialNumber + "/CN1296372B01T2/");
                one.put("Version", "A00");
            }
            case "computersystemproduct" -> {
                one.put("IdentifyingNumber", machine.serialNumber);
                one.put("Name", machine.model);
                one.put("UUID", machine.systemUuid);
                one.put("Vendor", machine.manufacturer);
                one.put("Version", "");
            }
            case "processor" -> {
                one.put("Caption", MachineProfile.CPU_IDENTIFIER.replace(", GenuineIntel", ""));
                one.put("CurrentClockSpeed", String.valueOf(MachineProfile.CPU_MHZ));
                one.put("DeviceID", "CPU0");
                one.put("Manufacturer", "GenuineIntel");
                one.put("MaxClockSpeed", String.valueOf(MachineProfile.CPU_MHZ));
                one.put("Name", MachineProfile.CPU_NAME);
                one.put("NumberOfCores", String.valueOf(machine.processors));
                one.put("NumberOfLogicalProcessors", String.valueOf(machine.processors));
                one.put("ProcessorId", MachineProfile.CPU_ID);
                one.put("SocketDesignation", "U3E1");
            }
            case "diskdrive" -> {
                one.put("Caption", MachineProfile.DISK_MODEL);
                one.put("DeviceID", "\\\\.\\PHYSICALDRIVE0");
                one.put("Index", "0");
                one.put("InterfaceType", "SCSI");
                one.put("MediaType", "Fixed hard disk media");
                one.put("Model", MachineProfile.DISK_MODEL);
                one.put("Partitions", "3");
                one.put("SerialNumber", "0000_0000_0000_0010_0008_0D02_" + machine.volumeSerial.substring(0, 4) + "_"
                        + machine.volumeSerial.substring(4) + ".");
                one.put("Size", String.valueOf(MachineProfile.DISK_BYTES));
                one.put("Status", "OK");
            }
            case "logicaldisk" -> {
                one.put("Caption", "C:");
                one.put("Description", "Local Fixed Disk");
                one.put("DeviceID", "C:");
                one.put("DriveType", "3");
                one.put("FileSystem", "NTFS");
                one.put("FreeSpace", String.valueOf(MachineProfile.VOLUME_FREE_BYTES));
                one.put("Size", String.valueOf(MachineProfile.VOLUME_BYTES));
                one.put("VolumeName", "OS");
                one.put("VolumeSerialNumber", machine.volumeSerial);
            }
            case "operatingsystem" -> {
                one.put("BuildNumber", MachineProfile.OS_BUILD);
                one.put("Caption", MachineProfile.OS_CAPTION);
                one.put("CodeSet", "1252");
                one.put("CountryCode", "1");
                one.put("CSName", machine.hostName);
                one.put("FreePhysicalMemory", String.valueOf(machine.freeMemoryBytes >> 10));
                one.put("InstallDate", wmiDate(machine.installTime));
                one.put("LastBootUpTime", wmiDate(machine.bootTime));
                one.put("Locale", "0409");
                one.put("Manufacturer", "Microsoft Corporation");
                one.put("OSArchitecture", "64-bit");
                one.put("OSLanguage", "1033");
                one.put("RegisteredUser", machine.userName);
                one.put("SerialNumber", machine.productId);
                one.put("SystemDirectory", "C:\\Windows\\system32");
                one.put("TotalVisibleMemorySize", String.valueOf(machine.visibleMemoryBytes >> 10));
                one.put("Version", MachineProfile.OS_VERSION);
                one.put("WindowsDirectory", "C:\\Windows");
            }
            case "networkadapter" -> {
                one.put("AdapterType", "Ethernet 802.3");
                one.put("Description", MachineProfile.NIC_NAME);
                one.put("MACAddress", machine.macAddress.replace('-', ':'));
                one.put("Manufacturer", "Intel Corporation");
                one.put("Name", MachineProfile.NIC_NAME);
                one.put("NetConnectionID", "Ethernet");
                one.put("PhysicalAdapter", "TRUE");
                one.put("Speed", "1000000000");
            }
            case "networkadapterconfiguration" -> {
                one.put("DefaultIPGateway", "{\"" + machine.gateway + "\"}");
                one.put("Description", MachineProfile.NIC_NAME);
                one.put("DHCPEnabled", "TRUE");
                one.put("DHCPServer", machine.gateway);
                one.put("DNSHostName", machine.hostName);
                one.put("IPAddress", "{\"" + machine.localAddress + "\",\"" + machine.linkLocalAddress.replaceAll("%\\d+$", "") + "\"}");
                one.put("IPEnabled", "TRUE");
                one.put("IPSubnet", "{\"255.255.255.0\",\"64\"}");
                one.put("MACAddress", machine.macAddress.replace('-', ':'));
            }
            case "desktopmonitor" -> {
                one.put("Caption", "Generic PnP Monitor");
                one.put("MonitorManufacturer", "(Standard monitor types)");
                one.put("Name", "Generic PnP Monitor");
                one.put("ScreenHeight", String.valueOf(machine.screenHeight));
                one.put("ScreenWidth", String.valueOf(machine.screenWidth));
            }
            case "videocontroller" -> {
                one.put("AdapterRAM", "1073741824");
                one.put("Caption", MachineProfile.GPU_NAME);
                one.put("CurrentHorizontalResolution", String.valueOf(machine.screenWidth));
                one.put("CurrentRefreshRate", String.valueOf(MachineProfile.SCREEN_REFRESH_HZ));
                one.put("CurrentVerticalResolution", String.valueOf(machine.screenHeight));
                one.put("DriverVersion", "31.0.101.2111");
                one.put("Name", MachineProfile.GPU_NAME);
                one.put("VideoProcessor", "Intel(R) HD Graphics Family");
            }
            case "antivirusproduct" -> {
                one.put("displayName", "Windows Defender");
                one.put("pathToSignedProductExe", "windowsdefender://");
                one.put("pathToSignedReportingExe", "%ProgramFiles%\\Windows Defender\\MsMpeng.exe");
                one.put("productState", "397568");
            }
            case "process" -> {
                List<Map<String, String>> processes = new ArrayList<>();
                for (MachineProfile.ProcessEntry entry : machine.processes()) {
                    Map<String, String> process = new LinkedHashMap<>();
                    boolean own = entry.pid() == machine.processId;
                    String path = own ? machine.javaExecutable(javaHome) : executablePath(entry.image());
                    process.put("Caption", entry.image());
                    process.put("CommandLine", own ? "\"" + path + "\" -jar \"" + vm.env.samplePath() + "\"" : "");
                    process.put("ExecutablePath", path);
                    process.put("Handle", String.valueOf(entry.pid()));
                    process.put("Name", entry.image());
                    process.put("ProcessId", String.valueOf(entry.pid()));
                    process.put("SessionId", String.valueOf(entry.session()));
                    process.put("WorkingSetSize", String.valueOf((long) entry.memoryKb() << 10));
                    processes.add(process);
                }
                processes.sort(Comparator.comparingLong(p -> Long.parseLong(p.get("ProcessId"))));
                return processes;
            }
            default -> {
                return List.of();
            }
        }
        return List.of(one);
    }

    /**
     * Where a listed program lives, for those a normal user may see. Windows hides the paths of
     * system and service processes from non-administrators, so wmic shows them empty.
     */
    private static String executablePath(String image) {
        return switch (image) {
            case "explorer.exe" -> "C:\\Windows\\explorer.exe";
            case "sihost.exe", "taskhostw.exe", "ctfmon.exe", "RuntimeBroker.exe", "dllhost.exe", "smartscreen.exe" ->
                    "C:\\Windows\\System32\\" + image;
            case "chrome.exe" -> "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
            default -> "";
        };
    }

    /** A time in WMI's {@code yyyyMMddHHmmss.ffffff+UUU} form, in the machine's local time. */
    private static String wmiDate(long millis) {
        java.time.ZonedDateTime time = Instant.ofEpochMilli(millis).atZone(MachineProfile.TIME_ZONE);
        int offsetMinutes = time.getOffset().getTotalSeconds() / 60;
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmss.000000", Locale.US).format(time)
                + (offsetMinutes < 0 ? "-" : "+") + String.format("%03d", Math.abs(offsetMinutes));
    }

    // ---------------------------------------------------------------- systeminfo, ipconfig, getmac

    private static String systeminfo(VirtualMachine vm) {
        MachineProfile machine = vm.env.machine;
        DateTimeFormatter local = DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a", Locale.US).withZone(MachineProfile.TIME_ZONE);
        long visibleMb = machine.visibleMemoryBytes >> 20;
        long freeMb = machine.freeMemoryBytes >> 20;
        long pageFileMb = 2432;
        String indent = " ".repeat(27);
        StringBuilder out = new StringBuilder(CRLF);
        field(out, "Host Name", machine.hostName);
        field(out, "OS Name", MachineProfile.OS_CAPTION);
        field(out, "OS Version", MachineProfile.OS_VERSION + " N/A Build " + MachineProfile.OS_BUILD);
        field(out, "OS Manufacturer", "Microsoft Corporation");
        field(out, "OS Configuration", "Standalone Workstation");
        field(out, "OS Build Type", "Multiprocessor Free");
        field(out, "Registered Owner", machine.userName);
        field(out, "Registered Organization", "");
        field(out, "Product ID", machine.productId);
        field(out, "Original Install Date", local.format(Instant.ofEpochMilli(machine.installTime)));
        field(out, "System Boot Time", local.format(Instant.ofEpochMilli(machine.bootTime)));
        field(out, "System Manufacturer", machine.manufacturer);
        field(out, "System Model", machine.model);
        field(out, "System Type", "x64-based PC");
        field(out, "Processor(s)", "1 Processor(s) Installed.");
        out.append(indent).append("[01]: ").append(MachineProfile.CPU_IDENTIFIER.replace(",", "")).append(" ~")
                .append(MachineProfile.CPU_MHZ).append(" Mhz").append(CRLF);
        field(out, "BIOS Version", machine.manufacturer + " " + MachineProfile.BIOS_VERSION + ", 4/11/2023");
        field(out, "Windows Directory", "C:\\Windows");
        field(out, "System Directory", "C:\\Windows\\system32");
        field(out, "Boot Device", "\\Device\\HarddiskVolume1");
        field(out, "System Locale", "en-us;English (United States)");
        field(out, "Input Locale", "en-us;English (United States)");
        field(out, "Time Zone", MachineProfile.TIME_ZONE_DISPLAY);
        field(out, "Total Physical Memory", String.format(Locale.US, "%,d MB", visibleMb));
        field(out, "Available Physical Memory", String.format(Locale.US, "%,d MB", freeMb));
        field(out, "Virtual Memory: Max Size", String.format(Locale.US, "%,d MB", visibleMb + pageFileMb));
        field(out, "Virtual Memory: Available", String.format(Locale.US, "%,d MB", freeMb + pageFileMb / 2));
        field(out, "Virtual Memory: In Use", String.format(Locale.US, "%,d MB", visibleMb - freeMb + pageFileMb / 2));
        field(out, "Page File Location(s)", "C:\\pagefile.sys");
        field(out, "Domain", "WORKGROUP");
        field(out, "Logon Server", "\\\\" + machine.hostName);
        field(out, "Hotfix(s)", "5 Hotfix(s) Installed.");
        String[] hotfixes = {"KB5031988", "KB5015684", "KB5032189", "KB5031539", "KB5032907"};
        for (int i = 0; i < hotfixes.length; i++) {
            out.append(indent).append(String.format("[%02d]: ", i + 1)).append(hotfixes[i]).append(CRLF);
        }
        field(out, "Network Card(s)", "1 NIC(s) Installed.");
        out.append(indent).append("[01]: ").append(MachineProfile.NIC_NAME).append(CRLF)
                .append(indent).append("      Connection Name: Ethernet").append(CRLF)
                .append(indent).append("      DHCP Enabled:    Yes").append(CRLF)
                .append(indent).append("      DHCP Server:     ").append(machine.gateway).append(CRLF)
                .append(indent).append("      IP address(es)").append(CRLF)
                .append(indent).append("      [01]: ").append(machine.localAddress).append(CRLF)
                .append(indent).append("      [02]: ").append(machine.linkLocalAddress.replaceAll("%\\d+$", "")).append(CRLF);
        // A virtual machine shows "A hypervisor has been detected." here instead.
        field(out, "Hyper-V Requirements", "VM Monitor Mode Extensions: Yes");
        out.append(indent).append("Virtualization Enabled In Firmware: Yes").append(CRLF)
                .append(indent).append("Second Level Address Translation: Yes").append(CRLF)
                .append(indent).append("Data Execution Prevention Available: Yes").append(CRLF);
        return out.toString();
    }

    /** One {@code systeminfo} line: the label and colon padded to 27 characters, then the value. */
    private static void field(StringBuilder out, String label, String value) {
        out.append(pad(label + ":", 27)).append(value).append(CRLF);
    }

    private static String ipconfig(MachineProfile machine, boolean all) {
        StringBuilder out = new StringBuilder(CRLF).append("Windows IP Configuration").append(CRLF).append(CRLF);
        if (all) {
            out.append("   Host Name . . . . . . . . . . . . : ").append(machine.hostName).append(CRLF)
                    .append("   Primary Dns Suffix  . . . . . . . : ").append(CRLF)
                    .append("   Node Type . . . . . . . . . . . . : Hybrid").append(CRLF)
                    .append("   IP Routing Enabled. . . . . . . . : No").append(CRLF)
                    .append("   WINS Proxy Enabled. . . . . . . . : No").append(CRLF);
        }
        out.append(CRLF).append("Ethernet adapter Ethernet:").append(CRLF).append(CRLF)
                .append("   Connection-specific DNS Suffix  . : ").append(CRLF);
        if (all) {
            out.append("   Description . . . . . . . . . . . : ").append(MachineProfile.NIC_NAME).append(CRLF)
                    .append("   Physical Address. . . . . . . . . : ").append(machine.macAddress).append(CRLF)
                    .append("   DHCP Enabled. . . . . . . . . . . : Yes").append(CRLF)
                    .append("   Autoconfiguration Enabled . . . . : Yes").append(CRLF);
        }
        out.append("   Link-local IPv6 Address . . . . . : ").append(machine.linkLocalAddress).append(all ? "(Preferred)" : "").append(CRLF)
                .append("   IPv4 Address. . . . . . . . . . . : ").append(machine.localAddress).append(all ? "(Preferred)" : "").append(CRLF)
                .append("   Subnet Mask . . . . . . . . . . . : 255.255.255.0").append(CRLF);
        if (all) {
            DateTimeFormatter local = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy h:mm:ss a", Locale.US)
                    .withZone(MachineProfile.TIME_ZONE);
            out.append("   Lease Obtained. . . . . . . . . . : ").append(local.format(Instant.ofEpochMilli(machine.bootTime + 9_000)))
                    .append(CRLF)
                    .append("   Lease Expires . . . . . . . . . . : ")
                    .append(local.format(Instant.ofEpochMilli(machine.bootTime + 9_000 + 86_400_000L))).append(CRLF);
        }
        out.append("   Default Gateway . . . . . . . . . : ").append(machine.gateway).append(CRLF);
        if (all) {
            out.append("   DHCP Server . . . . . . . . . . . : ").append(machine.gateway).append(CRLF)
                    .append("   DNS Servers . . . . . . . . . . . : ").append(machine.gateway).append(CRLF)
                    .append("   NetBIOS over Tcpip. . . . . . . . : Enabled").append(CRLF);
        }
        return out.toString();
    }

    private static String getmac(MachineProfile machine, String flags) {
        String transport = "\\Device\\Tcpip_{" + machine.systemUuid.substring(9) + "}";
        boolean verbose = flags.contains("/v");
        if (flags.contains("csv")) {
            return verbose
                    ? "\"Connection Name\",\"Network Adapter\",\"Physical Address\",\"Transport Name\"" + CRLF
                            + "\"Ethernet\",\"" + MachineProfile.NIC_NAME + "\",\"" + machine.macAddress + "\",\"" + transport + "\"" + CRLF
                    : "\"Physical Address\",\"Transport Name\"" + CRLF + "\"" + machine.macAddress + "\",\"" + transport + "\"" + CRLF;
        }
        if (verbose) {
            return CRLF + "Connection Name Network Adapter Physical Address    Transport Name" + CRLF
                    + "=============== =============== =================== ==========================================================" + CRLF
                    + "Ethernet        Intel(R) Ethern " + pad(machine.macAddress, 19) + " " + transport + CRLF;
        }
        return CRLF + "Physical Address    Transport Name" + CRLF
                + "=================== ==========================================================" + CRLF
                + pad(machine.macAddress, 19) + " " + transport + CRLF;
    }

    // ---------------------------------------------------------------- padding

    private static String pad(String text, int width) {
        return text.length() >= width ? text : text + " ".repeat(width - text.length());
    }

    private static String padLeft(String text, int width) {
        return text.length() >= width ? text : " ".repeat(width - text.length()) + text;
    }
}
