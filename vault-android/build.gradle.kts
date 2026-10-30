plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "io.github.gretchycat.infinivault.vault.android"
    compileSdk = 36
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
        sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("extNative"))
    } else {
        externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    }
}

if (file("/data/data/com.termux/files/usr/bin/clang").isFile) {
    val buildExtNative = tasks.register<Exec>("buildExtNative") {
        inputs.dir("src/main/cpp")
        outputs.dir(layout.buildDirectory.dir("extNative"))
        commandLine("sh", file("src/main/cpp/build-termux.sh"),
            layout.buildDirectory.dir("extNative").get().asFile, System.getProperty("java.home"))
    }
    tasks.named("preBuild").configure { dependsOn(buildExtNative) }
    tasks.withType<Test>().configureEach {
        dependsOn(buildExtNative)
        // JNI is loaded at runtime, so Gradle cannot infer this test input from
        // the Java/Kotlin classpath. Re-run tests when the native library changes.
        inputs.dir(layout.buildDirectory.dir("extNative"))
        jvmArgs("-Djava.library.path=${layout.buildDirectory.dir("extNative/arm64-v8a").get().asFile}")
    }
}
dependencies {
    api("io.github.gretchycat.libinfinistore:store-android:0.1.0")
    api("io.github.gretchycat.libinfinistream:stream-core:0.1.0")
    implementation("com.github.omicronapps:7-Zip-JBinding-4Android:Release-16.02-2.03")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
