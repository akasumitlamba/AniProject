# JavaScript bridge methods must keep their names.
-keepclassmembers class app.anihome.launcher.web.** {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class app.anihome.launcher.controls.RadioService { *; }
