[![Build Status](https://github.com/natario1/MavenPublisher/workflows/Build/badge.svg?event=push)](https://github.com/natario1/MavenPublisher/actions)
[![Release](https://img.shields.io/github/release/natario1/MavenPublisher.svg)](https://github.com/natario1/MavenPublisher/releases)
[![Issues](https://img.shields.io/github/issues-raw/natario1/MavenPublisher.svg)](https://github.com/natario1/MavenPublisher/issues)

*Need support, consulting, or have any other business-related question? Feel free to <a href="mailto:mat.iavarone@gmail.com">get in touch</a>.*

*Like the project, make profit from it, or simply want to thank back? Please consider [sponsoring me](https://github.com/sponsors/natario1)!*

# MavenPublisher

A lightweight, handy tool for publishing your maven packages (for example, Android AARs, Java JARs, Kotlin KLibs)
to different kinds of maven repositories. Currently, [Bintray](https://bintray.com) and local directories are supported.

To use any of the publisher plugins, you must configure the plugin repository in your build script:

```kotlin
buildscript {
    repositories {
        jcenter()
        google()
    }
    dependencies {
        classpath("com.otaliastudios.tools:publisher:0.3.2")
    }
}
```

The publisher plugin uses an older version of itself to publish itself into Bintray's JCenter.
This means that you can check the plugin source code to see an example of how to use it.

For more examples, please take a look at [natario1/Egloo](https://github.com/natario1/Egloo), [natario1/Firestore](https://github.com/natario1/Firestore) or [natario1/Elements](https://github.com/natario1/Elements).

## Usage

To apply the plugin, declare it in your build script with the `com.otaliastudios.tools.publisher` id:

```groovy
apply plugin: 'com.otaliastudios.tools.publisher'
```

### Base Configuration

All publishers are configured with the same common fields, although specific publisher
implementations can have extra fields (for example, for authentication). The common configuration
is specified at the root level of the `publisher` block:

```kotlin
publisher {
    // Project name. Defaults to rootProject.name
    project.name = "MavenPublisher"
    
    // Project description. Can be null
    project.description = "Handy tool to publish maven packages in different repositories."
    
    // Package artifact. Defaults to project's archivesBaseName
    project.artifact = "publisher"
    
    // Package group id. Defaults to project's group
    project.group = "com.otaliastudios.tools"
    
    // Project url
    project.url = "https://github.com/natario1/MavenPublisher"
    
    // Project VCS url. Defaults to project.url
    project.vcsUrl = "https://github.com/natario1/MavenPublisher.git"
    
    // Project packaging. Automatically set to AAR for Android libraries
    project.packaging = "aar"
    
    // Project licenses
    project.addLicense(License.APACHE_2_0)
    project.addLicense("My license", "https://mylicense.org")
    
    // Release version. Defaults to project's version
    release.version = "0.1.4"
    
    // Release VCS tag. Defaults to "v${release.version}"
    release.vcsTag = "v0.1.4"
    
    // Release description. Defaults to "${project.name} {release.vcsTag}"
    release.description = "New release"
    
    // Release sources
    release.setSources(Release.SOURCES_AUTO) // creates a sources Jar
    release.setSources(sourcesJar.get())
    
    // Release docs
    release.setDocs(Release.DOCS_AUTO) // create a docs Jar
    release.setDocs(dokkaJar.get())
}
```

Actual publishers can then be registered in this block by using their respective functions,
for example:

```kotlin
publisher {
    // Common configuration...
    project.name = "Project name"
    project.description = "Project description"

    bintray {
        // Override some fields or add missing ones
        project.name = "Project name for bintray"
        release.version = "1.0.0-nightly"
    }

    directory {
        // Override some fields or add missing ones
        project.description = "Project description for local directory"
        release.version = "1.0.0-rc1"
    }
}
```

### Specifying the publication contents

You have two options to specify the publication contents. MavenPublisher will try to infer default
values based on the currently applied plugin.

- `publisher.component` : specifies the name of a `SoftwareComponent`. Defaults to "release" for Android projects and "java" for java projects, both of which create components for you.
- `publisher.publication` : specifies the name of a `MavenPublication`. Defaults to null. When this is set, we'll use the publication's `SoftwareComponent` and modify the other publication fields to match the publisher configuration.

Typically, you should specify either one or the other.

### Secret values

Some sensitive values, especially in the auth configuration, are declared as secret.
In this case, instead of passing the real value, you are supposed to pass a **key** to the real
value.

The publisher will use this key and look for the value as follows:

- Look in environment variables
- Search with `project.findProperty(key)`
- Search in `local.properties` file, if present

### Publication tasks

The publisher plugin will add a task named `:publishTo` followed by the publication name.
The publication name depends on the publisher being used (for example, bintray) and the name
that is passed to the configuration. For example:

```kotlin
publisher {
    // Common configuration ...

    bintray {
        // Bintray onfiguration...
    }
    bintray("foo") {
        // Bintray configuration for foo...
    }
    bintray("bar") {
        // Bintray configuration for bar...
    }
    directory {
        // Local dir configuration...
    }
    directory("abc") {
        // Local dir configuration...
    }
}
```

In the example above, the following tasks will be available:

- `:publishToBintray`: publishes the default bintray configuration
- `:publishToBintrayFoo`: publishes the `foo` bintray configuration
- `:publishToBintrayBar`: publishes the `bar` bintray configuration
- `:publishToDirectory`: publishes the default directory configuration
- `:publishToDirectoryAbc`: publishes the `abc` bintray configuration
- `:publishAllBintray`: publishes all "bintray" publications
- `:publishAllDirectory`: publishes all "directory" publications
- `:publishAll`: publishes everything

## Local repository publisher

In addition to the common configuration fields, the local publisher will ask you for a path to the
local directory where the final package should be published. It can be set as follows:

```kotlin
publisher {
    // Common configuration...
    directory {
        // Directory configuration...
        directory = "build/output"
    }

    // If needed, you can add other named publications.
    directory("other") { ... }
}
```

As described earlier, all the configuration fields that are set in the `directory` block will override
the root level values.

If the directory is not set, the local publisher will publish into the "maven local" repository,
typically `$USER_HOME/.m2/repository` (see [docs](https://docs.gradle.org/current/dsl/org.gradle.api.artifacts.dsl.RepositoryHandler.html#org.gradle.api.artifacts.dsl.RepositoryHandler:mavenLocal())).

## Bintray publisher

In addition to the common configuration fields, to authenticate to Bintray, you will need to pass
a user name, a user key and the Bintray repo name as follows:

```kotlin
publisher {
    // Common configuration...
    bintray {
        // Bintray configuration...
        auth.user = "BINTRAY_USER" // defaults to "auth.user"
        auth.key = "BINTRAY_KEY" // defaults to "auth.key"
        auth.repo = "BINTRAY_REPO" // defaults to "auth.repo"
        dryRun = false // true for a dry-run publication for testing
    }

    // If needed, you can add other named publications.
    bintray("other") { ... }
}
```

As described earlier, all the configuration fields that are set in the `bintray` block will override
the root level values.

Note that the `auth` values are [Secret values](#secret-values), so instead of passing the real
value in plain text, a key (to an environment variable or local property) should be passed.

Since there is a default key, there is no need to declare your own keys in the plugin configuration -
you can simply provide values for the default keys (for example, as env properties).
