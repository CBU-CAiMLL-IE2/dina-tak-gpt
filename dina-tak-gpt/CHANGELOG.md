# Change Log

- Supplied extracted TAK Server libraries locally at `lib/libs/tak/UserManager.jar` and `lib/libs/tak/takserver-plugins-5.7-RELEASE-43.jar` for compilation; these files are not committed.
- Updated `lib/build.gradle` to use `lib/libs/tak/*.jar` as compile-only dependencies instead of the remote TAK plugin API artifact; removed the TAK Maven repository and its credential requirements.
- Updated `lib/src/main/java/com/google/common/collect/ImmutableMap.java` to pass `true` to the two Guava factory calls, matching the pinned Guava API while preserving duplicate-key rejection.
- Updated `lib/src/main/java/tak/server/plugins/TAKChatBotBase.java` to route `vertex-ai` bots to `VertexAIChatManager`.
- Updated `lib/src/main/java/tak/server/plugins/agent/VertexAIChatManager.java` to send the incoming chat message, use ADC instead of the credential-file setting, select the global API endpoint when requested, and close each request's model client.
- Replaced example bots in `conf/plugins/tak.server.plugins.TAKChatBotBase.yaml` and `lib/conf/plugins/tak.server.plugins.TAKChatBotBase.yaml` with DINA TAK GPT using a project ID placeholder, model `gemini-3.8-flash`, location `global`, and group `__ANON__`.
- Updated `.gitignore` to exclude imported `lib/libs/tak/*.jar` files; each person cloning the repository must supply these libraries manually.
- Updated `lib/build.gradle` to exclude the transitive `google-adk-dev` dependency so its Spring Boot web server and auto-configuration are not bundled into the TAK plugin.
- Updated `lib/build.gradle` to merge service descriptors so the shaded JAR retains gRPC DNS resolvers, transports, and load balancers.
- Added client-initialization and response-timing logs in `lib/src/main/java/tak/server/plugins/agent/VertexAIChatManager.java` to locate delays in model requests.
- Updated `lib/build.gradle` to exclude the legacy copied `ImmutableMap.java` from compilation and use the complete pinned Guava library, including `buildKeepingLast()` required by Google GAX.
- Updated `lib/src/main/java/tak/server/plugins/TAKChatBotBase.java` to log Java linkage errors and return the existing chat failure response without retrying incompatible dependencies.
- Updated `lib/src/main/java/tak/server/plugins/messages/TAKChatGenerator.java` to use a unique `GeoChat.<sender>.<recipient>.<message UUID>` event UID for each reply instead of reusing the bot's presence UID.
- Updated `lib/src/main/java/tak/server/plugins/messages/TAKChatGenerator.java` to explicitly address replies through `destClientUids`, align the GeoChat conversation and recipient fields, include sender UID metadata, and reject replies with a missing caller UID.
- Removed the project ID from client-initialization logging in `lib/src/main/java/tak/server/plugins/agent/VertexAIChatManager.java`; real project IDs belong only in private deployment configuration.
