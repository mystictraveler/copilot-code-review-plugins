package com.copilotreview.eclipse;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

public class CopilotReviewService {

    public static final String MARKER_TYPE = "com.copilotreview.eclipse.reviewMarker";
    private static final ILog LOG = Platform.getLog(CopilotReviewService.class);
    private static CopilotReviewService instance;
    private final Gson gson = new Gson();

    private volatile String cachedToken;
    private volatile long tokenExpiry;

    public static synchronized CopilotReviewService getInstance() {
        if (instance == null) {
            instance = new CopilotReviewService();
        }
        return instance;
    }

    public boolean isGitProject(IFile file) {
        File dir = file.getProject().getLocation().toFile();
        while (dir != null) {
            if (new File(dir, ".git").exists()) return true;
            dir = dir.getParentFile();
        }
        return false;
    }

    public List<ReviewIssue> reviewFile(IFile file) throws Exception {
        String content = Files.readString(file.getLocation().toFile().toPath(), StandardCharsets.UTF_8);
        String fileName = file.getName();
        String lang = file.getFileExtension() != null ? file.getFileExtension() : "unknown";

        List<ReviewIssue> issues = callCopilotForReview(content, fileName, lang);
        applyMarkers(file, issues);
        return issues;
    }

    public void clearMarkers(IResource resource) {
        try {
            resource.deleteMarkers(MARKER_TYPE, true, IResource.DEPTH_INFINITE);
        } catch (Exception e) {
            LOG.warn("Failed to clear markers: " + e.getMessage());
        }
    }

    private void applyMarkers(IFile file, List<ReviewIssue> issues) throws Exception {
        file.deleteMarkers(MARKER_TYPE, true, IResource.DEPTH_ZERO);

        for (ReviewIssue issue : issues) {
            IMarker marker = file.createMarker(MARKER_TYPE);
            marker.setAttribute(IMarker.LINE_NUMBER, issue.line);
            marker.setAttribute(IMarker.MESSAGE, "[Copilot Review] " + issue.message);
            marker.setAttribute(IMarker.SOURCE_ID, "CopilotReview");

            switch (issue.severity) {
                case "error":
                    marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
                    break;
                case "warning":
                    marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_WARNING);
                    break;
                default:
                    marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                    break;
            }
        }
    }

    private List<ReviewIssue> callCopilotForReview(String code, String fileName, String lang) throws Exception {
        String token = getCopilotApiToken();
        String prompt = buildReviewPrompt(code, fileName, lang);

        JsonObject request = new JsonObject();
        JsonArray messages = new JsonArray();

        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "You are a code reviewer. You respond only with valid JSON arrays.");
        messages.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", prompt);
        messages.add(userMsg);

        request.add("messages", messages);
        request.addProperty("model", "gpt-4o");
        request.addProperty("temperature", 0.1);
        request.addProperty("stream", false);

        URL url = URI.create("https://api.githubcopilot.com/chat/completions").toURL();
        HttpURLConnection conn = openConnection(url);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Editor-Version", "Eclipse/4.30");
        conn.setRequestProperty("Editor-Plugin-Version", "copilot-code-review/0.0.1");
        conn.setRequestProperty("Openai-Organization", "github-copilot");
        conn.setRequestProperty("Copilot-Integration-Id", "vscode-chat");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setDoOutput(true);

        byte[] body = gson.toJson(request).getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody;
            try {
                errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                errorBody = "unreadable";
            }
            throw new RuntimeException("Copilot API returned " + responseCode + ": " + errorBody);
        }

        String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return extractReviewFromResponse(responseBody);
    }

    private String getCopilotApiToken() throws Exception {
        if (cachedToken != null && System.currentTimeMillis() / 1000 < tokenExpiry - 300) {
            return cachedToken;
        }

        String oauthToken = readCopilotOAuthToken();
        if (oauthToken == null) {
            throw new IllegalStateException(
                "Could not find GitHub Copilot OAuth token. Make sure you are signed in to GitHub Copilot.");
        }

        URL url = URI.create("https://api.github.com/copilot_internal/v2/token").toURL();
        HttpURLConnection conn = openConnection(url);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "token " + oauthToken);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "CopilotCodeReview-Eclipse/0.0.1");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            String errorBody;
            try {
                errorBody = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                errorBody = "";
            }
            throw new RuntimeException("Failed to get Copilot token (HTTP " + responseCode + "): " + errorBody);
        }

        String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        cachedToken = json.get("token").getAsString();
        tokenExpiry = json.get("expires_at").getAsLong();

        return cachedToken;
    }

    private String readCopilotOAuthToken() {
        String homeDir = System.getProperty("user.home");
        String[] configPaths = {
            homeDir + "/.config/github-copilot/hosts.json",
            homeDir + "/.config/github-copilot/apps.json",
            homeDir + "/Library/Application Support/github-copilot/hosts.json",
            homeDir + "/Library/Application Support/github-copilot/apps.json",
            homeDir + "/AppData/Local/github-copilot/hosts.json",
            homeDir + "/AppData/Local/github-copilot/apps.json"
        };

        for (String path : configPaths) {
            File file = new File(path);
            if (!file.exists()) continue;

            try {
                String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(content).getAsJsonObject();

                for (String key : json.keySet()) {
                    JsonElement entry = json.get(key);
                    if (entry != null && entry.isJsonObject()) {
                        JsonObject obj = entry.getAsJsonObject();
                        if (obj.has("oauth_token")) {
                            return obj.get("oauth_token").getAsString();
                        }
                    }
                }

                if (json.has("oauth_token")) {
                    return json.get("oauth_token").getAsString();
                }
            } catch (Exception e) {
                LOG.info("Could not read Copilot config at " + path + ": " + e.getMessage());
            }
        }

        return null;
    }

    private List<ReviewIssue> extractReviewFromResponse(String responseBody) {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) return List.of();

        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        String content = message.has("content") ? message.get("content").getAsString() : null;
        if (content == null) return List.of();

        return parseResponse(content);
    }

    List<ReviewIssue> parseResponse(String response) {
        String jsonStr = response.trim();

        // Strip markdown fences
        Pattern fencePattern = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");
        Matcher fenceMatcher = fencePattern.matcher(jsonStr);
        if (fenceMatcher.find()) {
            jsonStr = fenceMatcher.group(1).trim();
        }

        // Find JSON array
        Pattern arrayPattern = Pattern.compile("\\[\\s*[\\s\\S]*]");
        Matcher arrayMatcher = arrayPattern.matcher(jsonStr);
        if (!arrayMatcher.find()) return List.of();
        jsonStr = arrayMatcher.group();

        try {
            List<Map<String, Object>> raw = gson.fromJson(jsonStr,
                    new TypeToken<List<Map<String, Object>>>() {}.getType());
            List<ReviewIssue> issues = new ArrayList<>();
            for (Map<String, Object> map : raw) {
                Number line = (Number) map.get("line");
                String severity = (String) map.get("severity");
                String msg = (String) map.get("message");
                if (line == null || msg == null) continue;
                issues.add(new ReviewIssue(line.intValue(), severity != null ? severity : "warning", msg));
            }
            return issues;
        } catch (Exception e) {
            LOG.warn("Failed to parse review response: " + e.getMessage());
            return List.of();
        }
    }

    private String buildReviewPrompt(String code, String fileName, String lang) {
        return "Review the following " + lang + " file \"" + fileName + "\" for bugs, security issues, performance problems, and code quality concerns.\n\n"
                + "For each issue found, respond with ONLY a JSON array. Each element must have:\n"
                + "- \"line\": the 1-based line number\n"
                + "- \"severity\": one of \"error\", \"warning\", \"info\", \"hint\"\n"
                + "- \"message\": a concise description of the issue\n\n"
                + "If no issues are found, return an empty array: []\n\n"
                + "Do NOT include any text outside the JSON array. No markdown fences.\n\n"
                + "Code:\n" + code;
    }

    private HttpURLConnection openConnection(URL url) throws Exception {
        // Use Eclipse's proxy service if available
        BundleContext context = Activator.getDefault().getBundle().getBundleContext();
        ServiceReference<IProxyService> proxyRef = context.getServiceReference(IProxyService.class);

        if (proxyRef != null) {
            IProxyService proxyService = context.getService(proxyRef);
            if (proxyService != null && proxyService.isProxiesEnabled()) {
                IProxyData[] proxyData = proxyService.select(url.toURI());
                for (IProxyData pd : proxyData) {
                    if (pd.getHost() != null) {
                        LOG.info("Using Eclipse proxy: " + pd.getHost() + ":" + pd.getPort());
                        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(pd.getHost(), pd.getPort()));
                        return (HttpURLConnection) url.openConnection(proxy);
                    }
                }
            }
        }

        return (HttpURLConnection) url.openConnection();
    }
}
