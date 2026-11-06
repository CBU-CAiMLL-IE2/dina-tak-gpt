package tak.server.plugins.agent;

import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import atakmap.commoncommo.protobuf.v1.MessageOuterClass.Message;
import tak.server.cot.CotEventContainer;
import tak.server.proto.StreamingProtoBufHelper;
import tak.server.plugins.messages.TAKMessageGenerator;

/** Keeps recent CoT events with their TAK routing groups and validates outbound events. */
public final class CotEventStore {
    private static final int MAX_EVENTS = 500;
    private static final int MAX_XML_CHARS = 32_768;
    private final List<String> typePrefixes;
    private final Set<String> botGroups;
    private final Map<String, StoredEvent> events = new LinkedHashMap<>();

    private record StoredEvent(String xml, Set<String> groups, Instant expires) { }

    public CotEventStore(List<String> typePrefixes, Set<String> botGroups) {
        if (typePrefixes.isEmpty() || typePrefixes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("cotTypePrefixes must contain nonempty prefixes");
        }
        this.typePrefixes = List.copyOf(typePrefixes);
        this.botGroups = Set.copyOf(botGroups);
    }

    public void observe(Message message) {
        if (Collections.disjoint(botGroups, message.getGroupsList())
                || message.getDestClientUidsCount() > 0 || message.getDestCallsignsCount() > 0
                || !allowedType(message.getPayload().getCotEvent().getType())) {
            return;
        }
        try {
            CotEventContainer cot = StreamingProtoBufHelper.proto2cot(message.getPayload());
            remember(cot.asXml(), Set.copyOf(message.getGroupsList()));
        } catch (Exception e) {
            // Malformed or unsupported traffic must not interrupt chat delivery.
            tak.server.plugins.LLMChatManager.LOGGER.debug("Ignoring invalid CoT event", e);
        }
    }

    private boolean allowedType(String type) {
        return typePrefixes.stream().anyMatch(type::startsWith);
    }

    public synchronized void remember(String xml, Set<String> groups) throws Exception {
        Element event = parse(xml).getDocumentElement();
        Instant expires = Instant.parse(event.getAttribute("stale"));
        if (expires.isBefore(Instant.now())) {
            return;
        }
        String uid = event.getAttribute("uid");
        if (uid.isBlank() || uid.length() > 256 || event.getAttribute("type").length() > 64) {
            return;
        }
        // Replacing an event moves it to the newest position for size-based eviction.
        events.remove(uid);
        events.put(uid, new StoredEvent(xml, Set.copyOf(groups), expires));
        events.entrySet().removeIf(entry -> entry.getValue().expires().isBefore(Instant.now()));
        while (events.size() > MAX_EVENTS) {
            events.remove(events.keySet().iterator().next());
        }
    }

    public synchronized List<String> list(Set<String> groups) throws Exception {
        List<String> result = new ArrayList<>();
        List<Map.Entry<String, StoredEvent>> recent = new ArrayList<>(events.entrySet());
        Collections.reverse(recent);
        for (Map.Entry<String, StoredEvent> entry : recent) {
            StoredEvent stored = entry.getValue();
            if (stored.expires().isAfter(Instant.now()) && !Collections.disjoint(groups, stored.groups())) {
                Element event = parse(stored.xml()).getDocumentElement();
                var contacts = event.getElementsByTagName("contact");
                String callsign = contacts.getLength() == 0 ? "" : ((Element) contacts.item(0)).getAttribute("callsign");
                result.add(entry.getKey() + " type=" + event.getAttribute("type")
                        + " callsign=" + callsign.substring(0, Math.min(callsign.length(), 256)));
                if (result.size() == 50) {
                    break;
                }
            }
        }
        return result;
    }

    public synchronized String get(String uid, Set<String> groups) {
        StoredEvent event = events.get(uid);
        if (event == null || event.expires().isBefore(Instant.now()) || Collections.disjoint(groups, event.groups())) {
            throw new IllegalArgumentException("CoT event unavailable in your group scope: " + uid);
        }
        return event.xml();
    }

    public String send(String xml, Set<String> groups) throws Exception {
        Document document = parse(xml);
        Element event = document.getDocumentElement();
        if (!allowedType(event.getAttribute("type")) || event.getAttribute("type").length() > 64) {
            throw new IllegalArgumentException("CoT type is not in cotTypePrefixes");
        }
        if (event.getElementsByTagName("event").getLength() != 0) {
            throw new IllegalArgumentException("Nested CoT events are unsupported");
        }
        // The plugin owns identity, timing and routing; a tool cannot overwrite another track.
        String uid = "TAK-GPT-" + UUID.randomUUID();
        event.setAttribute("uid", uid);
        event.setAttribute("version", "2.0");
        event.setAttribute("how", "m-g");
        event.setAttribute("time", Instant.now().toString());
        event.setAttribute("start", event.getAttribute("time"));
        event.setAttribute("stale", Instant.now().plusSeconds(3600).toString());
        if (event.getElementsByTagName("point").getLength() != 1) {
            throw new IllegalArgumentException("CoT requires one point");
        }
        Element point = (Element) event.getElementsByTagName("point").item(0);
        coordinates(Double.parseDouble(point.getAttribute("lat")), Double.parseDouble(point.getAttribute("lon")));
        for (String forbidden : List.of("marti", "__chat", "fileshare", "ackrequest", "ackresponse")) {
            if (event.getElementsByTagName(forbidden).getLength() != 0) {
                throw new IllegalArgumentException("Unsupported CoT detail: " + forbidden);
            }
        }
        var nodes = event.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            ((Element) nodes.item(i)).removeAttribute("endpoint");
        }
        String normalized = serialize(document);
        TAKMessageGenerator.getInstance().sendCot(normalized, groups, null);
        remember(normalized, groups);
        return uid;
    }

    public static void coordinates(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("Invalid latitude or longitude");
        }
    }

    public static Document parse(String xml) throws Exception {
        if (xml == null || xml.length() > MAX_XML_CHARS) {
            throw new IllegalArgumentException("CoT XML exceeds 32768 characters");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        if (!"event".equals(document.getDocumentElement().getTagName())) {
            throw new IllegalArgumentException("Expected one CoT event root");
        }
        return document;
    }

    public static String serialize(Document document) throws Exception {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        var writer = new java.io.StringWriter();
        factory.newTransformer().transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
