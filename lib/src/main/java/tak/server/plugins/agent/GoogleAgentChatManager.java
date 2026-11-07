package tak.server.plugins.agent;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.dom4j.DocumentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.models.BaseLlm;
import com.google.adk.models.Gemini;
import com.google.adk.models.langchain4j.LangChain4j;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.adk.tools.Annotations.Schema;
import com.google.adk.tools.FunctionTool;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Part;

import atakmap.commoncommo.protobuf.v1.MessageOuterClass.Message;
import dev.langchain4j.model.ollama.OllamaChatModel;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import tak.server.plugins.LLMChatManager;
import tak.server.plugins.config.TAKContext;
import tak.server.plugins.messages.CotType;
import tak.server.plugins.messages.TAKMessageGenerator;

/**
 * An agent that uses Google's ADK to interact with LLMs. Tools are provided for some basic TAK interactions.
 */
public class GoogleAgentChatManager implements LLMChatManager, AutoCloseable {
    private final InMemoryRunner runner;
    private final Set<String> groups;
    private Client geminiClient;
    private static final TAKMessageGenerator MSG_GENERATOR = TAKMessageGenerator.getInstance();

    private static final Logger LOGGER = LoggerFactory.getLogger(GoogleAgentChatManager.class);

    public GoogleAgentChatManager(Map<String, ? extends Object> config) {
        Object configuredGroups = config.get("groups");
        if (!(configuredGroups instanceof List<?> groupList) || groupList.isEmpty()
            || groupList.stream().anyMatch(group -> !(group instanceof String name) || name.isBlank())) {
            throw new IllegalArgumentException("google-agent requires a non-empty list of groups");
        }
        groups = groupList.stream().map(String.class::cast).collect(Collectors.toUnmodifiableSet());
        String botName = config.containsKey("botName") ? (String) config.get("botName") : "Agent TAK";

        String systemPrompt = loadSystemPromptFromConfig(config);

        // ** user can configure the system prompt, but we also add instructions on how to respond to requests for tools
        systemPrompt = "You are a helpful TAK assistant. Use the provided tool to create requested map markers. "
            + "Report success only when the tool succeeds; otherwise explain the failure. "
            + (systemPrompt == null ? "" : systemPrompt);

        LlmAgent agent = LlmAgent.builder()
            .name(botName.replaceAll("[^a-zA-Z0-9_]", "_").replaceFirst("^([0-9])", "_$1"))
            .model(createModel(config))
            .instruction(systemPrompt)
            .tools(
                FunctionTool.create(this, "createMarker")
            )
            .build();
        
        runner = new InMemoryRunner(agent);
    }

    private BaseLlm createModel(Map<String, ? extends Object> config) {
        if (config.containsKey("projectID") || config.containsKey("locationID")) {
            String project = requiredSetting(config, "projectID");
            String location = requiredSetting(config, "locationID");
            String modelName = requiredSetting(config, "modelName");
            try {
                geminiClient = Client.builder()
                    .vertexAI(true)
                    .project(project)
                    .location(location)
                    .credentials(GoogleCredentials.getApplicationDefault()
                        .createScoped("https://www.googleapis.com/auth/cloud-platform"))
                    .httpOptions(HttpOptions.builder().timeout(60_000).build())
                    .build();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to load ADC for the Gemini ADK agent", e);
            }
            LOGGER.info("Using Gemini ADK model {} in location {}", modelName, location);
            return new Gemini(modelName, geminiClient);
        }

        String host = config.containsKey("modelHost") ? (String) config.get("modelHost") : "127.0.0.1";
        int port = config.containsKey("modelPort") ? (Integer) config.get("modelPort") : 11434;
        String modelName = config.containsKey("modelName") ? (String) config.get("modelName") : "llama3.2:1b";
        return new LangChain4j(OllamaChatModel.builder()
            .baseUrl("http://" + host + ":" + port)
            .modelName(modelName)
            .build());
    }

    private static String requiredSetting(Map<String, ? extends Object> config, String name) {
        if (!(config.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException("google-agent requires " + name + " for Gemini");
        }
        return value;
    }

    /**
     * Example query: Create a map marker of type a-h-G with callsign foxtrot at latitude 42.382 and longitude -71.075
     * Example query: Create a map marker of type hostile ground with callsign foxtrot2 at latitude 42.382 and longitude -71.075
     * Example query: Create a hostile ground map marker with callsign foxtrot7 at latitude 42.382 and longitude -71.075
     */
    @Schema(name="create_tak_map_marker", description="Create a map marker in TAK. This is not for interacting with the Google Maps API, but for interacting with the TAK map which is unrelated to Google Maps. When successful, simply respond 'Marker added'")
    public Map<String, String> createMarker(@Schema(name = "type",
                description = "The type of marker to create. Types are specified as strings of characters with dashes between them, for example a-h-G, or from this set of values: 'hostile ground', 'friendly ground', 'neutral ground', 'unknown ground', 'hostile air', 'friendly air', 'neutral air', 'unknown air'")
        String type,
        @Schema(name = "callsign",
                description = "The callsign of the marker to create. This can be any alphanumeric string")
        String callsign,
        @Schema(name = "lat",
                description = "The latitude of the marker to create. These values are specified as numeric values with one or more decimal places")
        String lat,
        @Schema(name = "lon",
                description = "The longitude of the marker to create. These values are specified as numeric values with one or more decimal places")
        String lon) {
            LOGGER.info("createMarker called");

            Map<String,String> map = new HashMap<>();

            float latitude;
            float longitude;
            try {
                latitude = Float.parseFloat(lat);
                longitude = Float.parseFloat(lon);
            } catch (NumberFormatException | NullPointerException e) {
                return Map.of("status", "failure", "report", "Latitude and longitude must be numbers");
            }
            if (!Float.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Float.isFinite(longitude) || longitude < -180 || longitude > 180) {
                return Map.of("status", "failure", "report", "Coordinates are outside valid latitude/longitude ranges");
            }
            if (type == null || type.isBlank() || callsign == null || callsign.isBlank()) {
                return Map.of("status", "failure", "report", "Marker type and callsign are required");
            }

            String cotType;
            if(type.contains("-")) {
                cotType = type;
            } else {
                try {
                    CotType cotTypeValue = CotType.valueOf(type.toUpperCase().replace(" ", "_"));
                    cotType = cotTypeValue.getType();
                } catch (IllegalArgumentException e) {
                    LOGGER.error("Invalid marker type: " + type);
                    map.put("status", "failure");
                    map.put("report", "Invalid CotType: " + type);
                    return map;
                }
            }

            try {
                Message msg = MSG_GENERATOR.generateMarker(cotType, callsign, latitude, longitude, groups);
                MSG_GENERATOR.send(msg);
                map.put("status", "success");
                map.put("report", "Created a marker of type: " + type + ", callsign: " + callsign + ", at lat: " + lat + ", lon: " + lon);
                LOGGER.info("Created new map marker message: " + msg.toString());
            } catch (DocumentException e) {
                LOGGER.error("Exception creating message to send", e);
                map.put("status", "failure");
                map.put("report", "Unable to create marker");
            }

        return  map;
    }

    @Override
    public synchronized String sendChatRequest(String messageText, TAKContext context) throws IOException {
        String userId = context.getSenderUid();
        if (userId == null || userId.isBlank()) {
            throw new IOException("Cannot start an agent conversation without a sender UID");
        }
        Session session = runner.sessionService()
            .getSession(runner.appName(), userId, userId, Optional.empty())
            .switchIfEmpty(Single.defer(() ->
                runner.sessionService().createSession(runner.appName(), userId, null, userId)))
            .blockingGet();
        Content userMsg = Content.fromParts(Part.fromText(messageText));
        Flowable<Event> events = runner.runAsync(session.userId(), session.id(), userMsg);

        try {
            return events.filter(Event::finalResponse)
                .map(event -> event.content().flatMap(Content::parts).stream()
                    .flatMap(List::stream)
                    .filter(part -> !part.thought().orElse(false))
                    .flatMap(part -> part.text().stream())
                    .collect(Collectors.joining()))
                .filter(text -> !text.isBlank())
                .blockingLast("No response generated");
        } catch (RuntimeException e) {
            // A tool may already have sent a marker; do not retry the whole agent invocation.
            LOGGER.error("Error running the TAK agent", e);
            return "Error generating response; any completed map actions may already have been sent";
        }
    }

    @Override
    public synchronized void close() {
        if (geminiClient != null) {
            geminiClient.close();
        }
    }
}
