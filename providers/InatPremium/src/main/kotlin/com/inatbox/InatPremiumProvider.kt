package com.inatbox

class InatPremiumProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_XbOO87izu6ge2wsD9dQda4gF" },
) {
    override var mainUrl = "https://inattv257.cfd"
}
