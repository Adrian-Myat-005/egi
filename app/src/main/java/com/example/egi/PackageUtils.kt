package com.example.egi

object PackageUtils {
    private val PACKAGE_GROUPS = mapOf(
        "com.facebook.katana" to listOf(
            "com.facebook.orca", 
            "com.facebook.services", 
            "com.facebook.system", 
            "com.facebook.appmanager"
        ),
        "com.google.android.youtube" to listOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending"
        ),
        "com.google.android.gm" to listOf(
            "com.google.android.gms",
            "com.google.android.gsf"
        ),
        "com.google.android.apps.maps" to listOf(
            "com.google.android.gms",
            "com.google.android.gsf"
        ),
        "com.whatsapp" to listOf("com.whatsapp.w4b"),
        "com.instagram.android" to listOf(
            "com.facebook.katana",
            "com.facebook.services",
            "com.facebook.appmanager"
        ),
        "com.twitter.android" to listOf("com.x.android"),
        "com.ss.android.ugc.trill" to listOf("com.zhiliaoapp.musically")
    )

    fun expandPackageList(basePackages: Set<String>): Set<String> {
        val expanded = basePackages.toMutableSet()
        basePackages.forEach { pkg ->
            PACKAGE_GROUPS[pkg]?.let { expanded.addAll(it) }
            
            // Generic rule: If it's a major google app, always include GMS
            if (pkg.startsWith("com.google.android.apps.") || pkg.startsWith("com.google.android.youtube")) {
                expanded.add("com.google.android.gms")
                expanded.add("com.google.android.gsf")
            }
        }
        return expanded
    }
}
