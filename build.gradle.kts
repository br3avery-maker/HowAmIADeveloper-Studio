android {
    namespace = "com.chaoticka.sovereignshell" // Should match your base package
    compileSdk = 34 // (or your target version)

    defaultConfig {
        applicationId = "com.chaoticka.sovereignshell" // <-- Add this line here
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        
        // ... other default configs
    }
}
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}
