import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/**
 * This class creates a simple detector that detects logs suspected of being generated by bots.
 */
public class BotDetector {
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^(\\S+) - (\\S+) - \\[(.*?)\\] \"(GET|POST) (\\S+) HTTP/\\d\\.\\d\" (\\d{3}) \\d+ \"[^\"]*\" \"([^\"]*)\" (\\d+)$"
        );
    private static final DateTimeFormatter FORMATTER =
        DateTimeFormatter.ofPattern("dd/MM/yyyy:HH:mm:ss").withZone(ZoneId.of("UTC"));

    private static final Map<String, List<Instant>> ipRequestTimes = new HashMap<>();
    private static final Map<String, Integer> ipStaticCount = new HashMap<>();

    private static int count = 0, ua = 0, nostatic = 0, frequent = 0;
    
    /**
     * Run this method to run the detector
     */
    public static void main(String[] args) throws IOException {
        String filePath = "sample-log.log";
        BufferedReader reader = new BufferedReader(new FileReader(filePath));
        BufferedWriter writer = new BufferedWriter(new FileWriter("botdetector-output.txt"));

        writer.write("Potential Bot Requests:\n");

        String line;
        while ((line = reader.readLine()) != null) {
            processLine(line, writer);
        }

        summarize(writer);
        reader.close();
        writer.close();
        System.out.println("Output written to botdetector-output.txt");
    }
    
    /**
     * Process one line from the log
     */
    private static void processLine(String line, BufferedWriter writer) throws IOException {
        Matcher m = LOG_PATTERN.matcher(line);
        if (!m.matches()) return;

        String ip = m.group(1);
        String timestampStr = m.group(3);
        String method = m.group(4);
        String path = m.group(5);
        String userAgent = m.group(7);

        Instant timestamp = LocalDateTime.parse(timestampStr, DateTimeFormatter.ofPattern("dd/MM/yyyy:HH:mm:ss"))
            .atZone(ZoneOffset.UTC).toInstant();

        ipRequestTimes.computeIfAbsent(ip, k -> new ArrayList<>()).add(timestamp);

        if (isStaticResource(path)) {
            ipStaticCount.merge(ip, 1, Integer::sum);
        }

        boolean badUA = isBadUserAgent(userAgent);
        boolean noStatic = ipStaticCount.getOrDefault(ip, 0) == 0;
        boolean rapidFire = checkRapidRequests(ipRequestTimes.get(ip), timestamp);

        if (badUA) {
            ua++;
            writer.write(String.format("FLAGGED FOR BAD UA: %s [%s] %s \"%s\" UA=\"%s\"\n", ip, timestampStr, method, path, userAgent));
        }
        
        if (noStatic && ipRequestTimes.get(ip).size() > 3) {
            nostatic++;
            writer.write(String.format("FLAGGED FOR NO STATIC: %s [%s] %s \"%s\" UA=\"%s\"\n", ip, timestampStr, method, path, userAgent));
        }
        
        if (rapidFire) {
            frequent++;
            writer.write(String.format("FLAGGED FOR FREQUENT: %s [%s] %s \"%s\" UA=\"%s\"\n", ip, timestampStr, method, path, userAgent));
        }

        count++;
    }
    
    /**
     * Check if the request was made by a bad user agent.
     */
    private static boolean isBadUserAgent(String ua) {
        String lower = ua.toLowerCase();
        return lower.contains("curl") || lower.contains("python") || lower.contains("java") || ua.isEmpty();
    }
    
    /**
     * Check if there was access to a static resource. 
     */
    private static boolean isStaticResource(String path) {
        return path.endsWith(".jpg") || path.endsWith(".png") || path.endsWith(".css") || path.endsWith(".js");
    }
    
    /**
     * Check if the requests are being made too frequently by the same user. 
     */
    private static boolean checkRapidRequests(List<Instant> times, Instant now) {
        times.removeIf(t -> Duration.between(t, now).toSeconds() > 10);
        return times.size() > 5;
    }
    
    /**
     * Create a final summary of the model's analysis
     */
    private static void summarize(BufferedWriter writer) throws IOException {
        int total = ua + nostatic + frequent;
        double rate = (double) total / count * 100;

        writer.write("\nTotal Checked: " + count);
        writer.write("\nBad UA: " + ua);
        writer.write("\nNo Static: " + nostatic);
        writer.write("\nToo Frequent: " + frequent);
        writer.write("\nTotal flagged: " + total);
        writer.write("\nFlag rate: " + rate + "%");
    }
}
