package tak.server.plugins.agent;

import java.io.IOException;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import tak.server.plugins.LLMChatManager;
import tak.server.plugins.config.TAKContext;

/**
 * Sends chat messages to Vertex AI using Application Default Credentials.
 */
public class VertexAIChatManager implements LLMChatManager{
    private static final Logger LOGGER = LoggerFactory.getLogger(VertexAIChatManager.class);
    private final String projectID;
    private final String location;
    private final String modelName;

    public VertexAIChatManager(Map<String, ? extends Object> config) {
        if(!config.containsKey("projectID")
            || !config.containsKey("locationID")
            || !config.containsKey("modelName")){
                throw new RuntimeException("Unable to start, missing configuration parameter(s)");
        }

        projectID = (String)config.get("projectID");
        location = (String)config.get("locationID");
        modelName = (String)config.get("modelName");
    }

    @Override
    public String sendChatRequest(String messageText, TAKContext context) throws IOException {
        long startTime = System.nanoTime();
        LOGGER.info("Initializing Vertex AI client for location {}, model {}", location, modelName);
        var modelBuilder = VertexAiGeminiChatModel.builder()
            .project(projectID)
            .location(location)
            .modelName(modelName);

        // The global location uses the unprefixed API endpoint.
        if ("global".equals(location)) {
            modelBuilder.apiEndpoint("aiplatform.googleapis.com");
        }

        try (VertexAiGeminiChatModel model = modelBuilder.build()) {
            LOGGER.info("Vertex AI client initialized; requesting model response");
            String response = model.chat(messageText);
            LOGGER.info("Vertex AI response received after {} ms", (System.nanoTime() - startTime) / 1_000_000);
            return response;
        }
    }

}
