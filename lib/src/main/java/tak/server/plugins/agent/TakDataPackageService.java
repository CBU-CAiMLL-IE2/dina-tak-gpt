package tak.server.plugins.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.google.gson.JsonParser;

import org.w3c.dom.Element;

import tak.server.plugins.config.TAKContext;
import tak.server.plugins.messages.TAKMessageGenerator;

/** Builds owner-scoped packages and transfers them through a configured Marti mTLS identity. */
public final class TakDataPackageService {
    private static final int MAX_PACKAGE_BYTES = 25 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100;
    private final Path directory;
    private final Path attachments;
    private final String packageGroup;
    private final String botName;
    private final URI server;
    private final HttpClient client;

    public TakDataPackageService(Map<String, ? extends Object> config, Set<String> botGroups) throws Exception {
        directory = config.containsKey("packageDirectory") ? Path.of((String) config.get("packageDirectory")).toAbsolutePath() : null;
        attachments = config.containsKey("attachmentDirectory") ? Path.of((String) config.get("attachmentDirectory")).toRealPath() : null;
        packageGroup = (String) config.get("packageGroup");
        botName = (String) config.get("botName");
        if (directory != null && (packageGroup == null || !botGroups.contains(packageGroup))) {
            throw new IllegalArgumentException("packageDirectory requires packageGroup belonging to bot groups");
        }
        if (!config.containsKey("packageServerUrl")) {
            server = null;
            client = null;
            return;
        }
        if (directory == null) {
            throw new IllegalArgumentException("packageServerUrl requires packageDirectory");
        }
        server = URI.create((String) config.get("packageServerUrl"));
        if (!"https".equals(server.getScheme()) || server.getHost() == null || !"/Marti".equals(server.getPath())
                || server.getUserInfo() != null || server.getQuery() != null || server.getFragment() != null) {
            throw new IllegalArgumentException("packageServerUrl must be https://host:port/Marti without a trailing slash");
        }
        char[] keyPassword = password("TAK_GPT_KEYSTORE_PASSWORD");
        char[] trustPassword = password("TAK_GPT_TRUSTSTORE_PASSWORD");
        try {
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of((String) config.get("packageKeyStore")))) {
                keys.load(input, keyPassword);
            }
            KeyStore trust = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(Path.of((String) config.get("packageTrustStore")))) {
                trust.load(input, trustPassword);
            }
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keys, keyPassword);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(trust);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);
            client = HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
        } finally {
            java.util.Arrays.fill(keyPassword, '\0');
            java.util.Arrays.fill(trustPassword, '\0');
        }
    }

    private static char[] password(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing environment variable " + name);
        }
        return value.toCharArray();
    }

    private void authorize(Set<String> groups) {
        if (directory == null) {
            throw new IllegalStateException("Configure packageDirectory and packageGroup to enable packages");
        }
        if (!groups.contains(packageGroup)) {
            throw new IllegalArgumentException("Package operations require membership in packageGroup");
        }
    }

    private Path ownerDirectory(TAKContext context, Set<String> groups) throws Exception {
        authorize(groups);
        String owner = hash((context.getConnectionId() + "\n" + context.getSenderUid() + "\n"
                + String.join("\n", new TreeSet<>(groups))).getBytes(StandardCharsets.UTF_8));
        Path path = directory.resolve(owner);
        Files.createDirectories(path);
        return path.toRealPath();
    }

    public List<String> listAttachments(Set<String> groups) throws IOException {
        authorize(groups);
        if (attachments == null) {
            return List.of();
        }
        try (var files = Files.list(attachments)) {
            return files.filter(path -> Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString()).sorted().limit(MAX_ENTRIES).toList();
        }
    }

    public String build(String name, List<String> filenames, List<String> uids, CotEventStore events,
            TAKContext context, Set<String> groups) throws Exception {
        Path owner = ownerDirectory(context, groups);
        if (name == null || name.isBlank() || name.length() > 100 || filenames.size() + uids.size() == 0
                || filenames.size() + uids.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Provide a name (1-100 characters) and 1-100 contents");
        }
        checkCapacity(owner);
        String id = UUID.randomUUID().toString();
        var manifest = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = manifest.createElement("MissionPackageManifest");
        root.setAttribute("version", "2");
        manifest.appendChild(root);
        Element configuration = manifest.createElement("Configuration");
        root.appendChild(configuration);
        parameter(configuration, "uid", id);
        parameter(configuration, "name", name);
        Element contents = manifest.createElement("Contents");
        root.appendChild(contents);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        long total = 0;
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (String filename : new java.util.LinkedHashSet<>(filenames)) {
                if (attachments == null || !Path.of(filename).getFileName().toString().equals(filename)) {
                    throw new IllegalArgumentException("Use an attachment name from listAttachments");
                }
                Path file = attachments.resolve(filename).toRealPath();
                if (!file.startsWith(attachments) || !Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("Attachment outside configured directory");
                }
                byte[] data;
                try (var input = Files.newInputStream(file)) {
                    data = input.readNBytes(MAX_PACKAGE_BYTES + 1);
                }
                total += data.length;
                if (total > MAX_PACKAGE_BYTES) {
                    throw new IOException("Package contents exceed 25 MiB");
                }
                addEntry(zip, contents, "files/" + filename, data, null);
            }
            for (String uid : new java.util.LinkedHashSet<>(uids)) {
                // Every exported event must be visible in the package's fixed distribution group.
                byte[] data = events.get(uid, Set.of(packageGroup)).getBytes(StandardCharsets.UTF_8);
                total += data.length;
                if (total > MAX_PACKAGE_BYTES) {
                    throw new IOException("Package contents exceed 25 MiB");
                }
                String entry = "cot/" + UUID.randomUUID() + ".cot";
                addEntry(zip, contents, entry, data, uid);
            }
            zip.putNextEntry(new ZipEntry("MANIFEST/manifest.xml"));
            zip.write(CotEventStore.serialize(manifest).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        if (bytes.size() > MAX_PACKAGE_BYTES) {
            throw new IOException("Compressed package exceeds 25 MiB");
        }
        Files.write(owner.resolve(id + ".zip"), bytes.toByteArray(), java.nio.file.StandardOpenOption.CREATE_NEW);
        return id;
    }

    private static void parameter(Element parent, String name, String value) {
        Element parameter = parent.getOwnerDocument().createElement("Parameter");
        parameter.setAttribute("name", name);
        parameter.setAttribute("value", value);
        parent.appendChild(parameter);
    }

    private static void addEntry(ZipOutputStream zip, Element contents, String entry, byte[] data, String uid) throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(data);
        zip.closeEntry();
        Element content = contents.getOwnerDocument().createElement("Content");
        content.setAttribute("ignore", "false");
        content.setAttribute("zipEntry", entry);
        if (uid != null) {
            parameter(content, "uid", uid);
            parameter(content, "isCoT", "true");
        }
        contents.appendChild(content);
    }

    public String send(String id, TAKContext context, Set<String> groups) throws Exception {
        Path owner = ownerDirectory(context, groups);
        requireServer();
        verifyTransferGroup();
        String filename = UUID.fromString(id) + ".zip";
        byte[] data;
        try (var input = Files.newInputStream(owner.resolve(filename))) {
            data = input.readNBytes(MAX_PACKAGE_BYTES + 1);
        }
        if (data.length > MAX_PACKAGE_BYTES) {
            throw new IOException("Package exceeds 25 MiB");
        }
        String boundary = "takgpt-" + UUID.randomUUID();
        String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"assetfile\"; filename=\""
                + filename + "\"\r\nContent-Type: application/zip\r\n\r\n";
        HttpRequest request = HttpRequest.newBuilder(URI.create(server + "/sync/missionupload?filename=" + filename
                + "&creatorUid=" + encode(botName) + "&tool=private"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(header),
                        HttpRequest.BodyPublishers.ofByteArray(data),
                        HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"))).build();
        // The upload response may contain an internal hostname; use the configured client-facing URL.
        exchange(request, 16_384);
        String url = server + "/sync/content?hash=" + hash(data);
        var document = CotEventStore.parse("<event><point lat=\"0\" lon=\"0\" hae=\"0\" ce=\"999999\" le=\"999999\"/><detail/></event>");
        Element event = document.getDocumentElement();
        String now = java.time.Instant.now().toString();
        event.setAttribute("version", "2.0");
        event.setAttribute("uid", UUID.randomUUID().toString());
        event.setAttribute("type", "b-f-t-r");
        event.setAttribute("how", "h-e");
        event.setAttribute("time", now);
        event.setAttribute("start", now);
        event.setAttribute("stale", java.time.Instant.now().plusSeconds(300).toString());
        Element share = document.createElement("fileshare");
        share.setAttribute("filename", filename);
        share.setAttribute("name", filename);
        share.setAttribute("senderUrl", url);
        share.setAttribute("sizeInBytes", Integer.toString(data.length));
        share.setAttribute("sha256", hash(data));
        share.setAttribute("senderUid", botName);
        share.setAttribute("senderCallsign", botName);
        event.getElementsByTagName("detail").item(0).appendChild(share);
        TAKMessageGenerator.getInstance().sendCot(CotEventStore.serialize(document), Set.of(packageGroup), context.getSenderUid());
        return "Uploaded package and sent transfer request to the requesting TAK user. Client receipt is not confirmed. " + url;
    }

    public String download(String sha256, TAKContext context, Set<String> groups) throws Exception {
        Path owner = ownerDirectory(context, groups);
        requireServer();
        verifyTransferGroup();
        checkCapacity(owner);
        if (!sha256.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("Expected SHA-256 hash");
        }
        byte[] data = exchange(HttpRequest.newBuilder(URI.create(server + "/sync/content?hash=" + sha256))
                .timeout(Duration.ofSeconds(60)).GET().build(), MAX_PACKAGE_BYTES);
        if (!hash(data).equalsIgnoreCase(sha256)) {
            throw new IOException("Downloaded package hash mismatch");
        }
        boolean manifest = false;
        long expanded = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES + 1 || entry.getName().contains("..")
                        || entry.getName().startsWith("/") || entry.getName().contains("\\")) {
                    throw new IOException("Unsafe package archive");
                }
                byte[] chunk = new byte[8192];
                int count;
                while ((count = zip.read(chunk)) != -1) {
                    expanded += count;
                    if (expanded > MAX_PACKAGE_BYTES) {
                        throw new IOException("Expanded package exceeds 25 MiB");
                    }
                }
                manifest |= "MANIFEST/manifest.xml".equals(entry.getName());
            }
        }
        if (!manifest) {
            throw new IOException("No TAK package manifest");
        }
        String id = UUID.randomUUID().toString();
        Files.write(owner.resolve(id + ".zip"), data, java.nio.file.StandardOpenOption.CREATE_NEW);
        return id;
    }

    private void requireServer() {
        if (client == null) {
            throw new IllegalStateException("Configure packageServerUrl, packageKeyStore and packageTrustStore for transfers");
        }
    }

    private void checkCapacity(Path owner) throws IOException {
        try (var existing = Files.list(owner); var all = Files.walk(directory, 2)) {
            if (existing.limit(20).count() >= 20 || all.filter(Files::isRegularFile).limit(200).count() >= 200) {
                throw new IOException("Package storage full (20 per caller scope, 200 total); an operator must remove old packages");
            }
        }
    }

    private void verifyTransferGroup() throws Exception {
        byte[] response = exchange(HttpRequest.newBuilder(URI.create(server + "/api/groups/all"))
                .timeout(Duration.ofSeconds(15)).GET().build(), 65_536);
        var data = JsonParser.parseString(new String(response, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("data");
        Set<String> names = new java.util.HashSet<>();
        for (var group : data) {
            names.add(group.getAsJsonObject().get("name").getAsString());
        }
        if (!names.equals(Set.of(packageGroup))) {
            throw new IOException("Transfer certificate must have access to exactly packageGroup; use a dedicated non-admin certificate");
        }
    }

    private byte[] exchange(HttpRequest request, int limit) throws Exception {
        HttpResponse<byte[]> response = client.send(request, info -> new LimitedBodySubscriber(limit));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Marti returned HTTP " + response.statusCode());
        }
        return response.body();
    }

    /** Enforces the size cap while downloading, including when Content-Length is absent. */
    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private long remaining;
        private Flow.Subscription subscription;

        private LimitedBodySubscriber(int limit) {
            remaining = limit;
        }

        @Override
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                remaining -= buffer.remaining();
            }
            if (remaining < 0) {
                subscription.cancel();
                delegate.onError(new IOException("Marti response exceeds configured size limit"));
            } else {
                delegate.onNext(buffers);
            }
        }

        @Override
        public void onError(Throwable error) { delegate.onError(error); }

        @Override
        public void onComplete() { delegate.onComplete(); }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
