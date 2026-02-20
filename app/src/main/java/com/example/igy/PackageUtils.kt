package com.example.igy

import kotlin.collections.ArrayDeque

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
        val expanded = mutableSetOf<String>()
        val queue = ArrayDeque<String>(basePackages)

        while (queue.isNotEmpty()) {
            val pkg = queue.removeFirst()
            if (expanded.add(pkg)) {
                // Add dependencies from the map
                PACKAGE_GROUPS[pkg]?.forEach { dep ->
                    if (!expanded.contains(dep)) {
                        queue.add(dep)
                    }
                }
                
                // Generic rule: If it's a major google app, always include GMS/GSF
                if (pkg.startsWith("com.google.android.apps.") || pkg.startsWith("com.google.android.youtube")) {
                    queue.add("com.google.android.gms")
                    queue.add("com.google.android.gsf")
                    queue.add("com.android.vending")
                }
            }
        }
        return expanded
    }
}
