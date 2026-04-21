# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /home/rkr/Android/Sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep all R inner classes so resource IDs accessed at runtime are not stripped
-keep class uk.coko.forge.kokey.R$* { *; }

# LocaleResourceUtils uses R.class.getPackage().getName() to look up resources by name
# at runtime via getIdentifier(). If R8 renames the package, getIdentifier() returns 0
# and the app crashes with Resources$NotFoundException.
-keeppackagenames uk.coko.forge.kokey.**
-keep class uk.coko.forge.kokey.latin.settings.SettingsFragment
-keep class uk.coko.forge.kokey.latin.settings.LanguagesSettingsFragment
-keep class uk.coko.forge.kokey.latin.settings.SingleLanguageSettingsFragment