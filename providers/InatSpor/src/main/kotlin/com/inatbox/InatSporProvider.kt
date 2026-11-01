package com.inatbox

class InatSporProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_KDLoyZzhSK8eO3SP7pl-lVah" },
) {
    override var mainUrl = "https://sprboxs.bar"
}
