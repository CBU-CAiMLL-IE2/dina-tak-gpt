# Setup Plugin JAR Libraries

These libraries are used when building the TAK server-side plugin.

Use JARs from the same TAK Server version as the server where the plugin will be deployed.

## Required Libraries

The final `lib/libs/tak/` directory should contain:

```text
lib/libs/tak/
|- takserver-plugins-<TAK_VERSION>.jar
|- UserManager.jar
```

For example:

```text
lib/libs/tak/
|- takserver-plugins-5.7-RELEASE-43.jar
|- UserManager.jar
```

## 1. Extract `takserver-plugins-<TAK_VERSION>.jar`

The plugin library is packaged inside:

```text
tak/utils/takserver-pm.jar
```

Create a temporary directory and extract `takserver-pm.jar`:

```bash
mkdir -p /tmp/takserver-pm
cd /tmp/takserver-pm

# Replace with the actual path to takserver-pm.jar in your server files.
jar xf <PATH_TO_TAKSERVER_PM_JAR>
```

The required plugin JAR is located at:

```text
BOOT-INF/lib/takserver-plugins-<TAK_VERSION>.jar
```

Copy it into the repository's `lib/libs/tak/` directory:

```bash
cp \
  "BOOT-INF/lib/takserver-plugins-<TAK_VERSION>.jar" \
  <PATH_TO_DINA_TAK_PLUGIN>/lib/libs/tak/
```

Verify the file:

```bash
ls -lh <PATH_TO_DINA_TAK_PLUGIN>/lib/libs/tak/
```

## 2. Copy `UserManager.jar`

`UserManager.jar` is included directly in the TAK Server package at:

```text
tak/utils/UserManager.jar
```

Copy it into the repository's `lib/libs/tak/` directory:

```bash
cp \
  <PATH_TO_TAK_SERVER>/tak/utils/UserManager.jar \
  <PATH_TO_DINA_TAK_PLUGIN>/lib/libs/tak/
```
