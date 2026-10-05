# JNI: native code looks these up by name.
-keep class app.orcaandroid.core.OrcaNative { *; }
-keep interface app.orcaandroid.core.ProgressListener { *; }
# open-bamboo-networking bridge (core/jni/ObnBridge.cpp): native methods and the progress
# callback it looks up by name.
-keep class app.orcaandroid.net.ObnNative { *; }
-keep interface app.orcaandroid.net.ObnNative$PrintListener { *; }
