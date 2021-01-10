buildscript {
    repositories {
        maven("build/maven")
        google()
        jcenter()
    }

    configurations.configureEach {
        resolutionStrategy.cacheChangingModulesFor(10, TimeUnit.SECONDS)
    }

    dependencies {
        classpath("io.deepmedia.tools:publisher:0.4.0")
        classpath("io.deepmedia.tools:grease:0.2.0") {
            isChanging = true
        }
    }
}

allprojects {
    repositories {
        mavenCentral()
        google()
        jcenter()
    }
}

tasks.register("clean", Delete::class) {
    delete(buildDir)
}
