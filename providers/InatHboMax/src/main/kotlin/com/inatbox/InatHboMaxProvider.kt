package com.inatbox

class InatHboMaxProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_0vOwWIPu3PDT0Jivm4ICbCYO" },
) {
    override var mainUrl = "https://diziboxen.help"
}
