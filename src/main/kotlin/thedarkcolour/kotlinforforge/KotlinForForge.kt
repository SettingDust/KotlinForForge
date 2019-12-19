package thedarkcolour.kotlinforforge

import net.minecraftforge.fml.common.Mod
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Set 'modLoader' in mods.toml to "kotlinforforge" and loaderVersion to "[1,)".
 */
@Mod("kotlinforforge")
object KotlinForForge {
    internal val logger: Logger = LogManager.getLogger()
}