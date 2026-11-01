package com.inatbox

class InatEbaTvProvider : InatPlatformProvider(
    InatCfgRegistry.ALL.first { it.id == "plat_B159Mech2izHkFn7NQD6AlOE" },
) {
    override var mainUrl = "https://diziboxen.help"
}
