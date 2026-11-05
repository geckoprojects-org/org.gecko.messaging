# Workspace setup

This workspace follows the **fennec-gradle** template fragment (as org.gecko.weather), on
Gradle 9 and bnd 7.4.0.

## Project coordinates (one place)

All project-specific values live in **`gradle.properties`** at the workspace root: `github_org`,
`github_repository`, `maven_group_id`. Gradle reads them natively; bnd imports the same file via
`-include: ${workspace}/gradle.properties` in `cnf/ext/fennec.bnd`.

`base-version` (2.0.0) is set in `cnf/ext/fennec.bnd`. The Java packages of the API carry their own
semantic versions in `package-info.java`.

## Libraries

`cnf/ext/fennec.bnd` enables `-library: fennec, fennecTest, fennecJacoco`, resolved from Maven
Central via `cnf/ext/central.mvn`. The Paho MQTT 5 client is the build of geckoprojects-org/paho,
`org.geckoprojects.paho:org.eclipse.paho.mqttv5.client:1.3.0-SNAPSHOT` from the Sonatype snapshot
repository.
