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
        classpath("com.otaliastudios.tools:publisher:0.3.3")
        classpath("com.otaliastudios.tools:grease:0.1.0") {
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

tasks.create("clean", Delete::class) {
    delete(buildDir)
}
