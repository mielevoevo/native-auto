package com.nativeauto.ui.map

import androidx.compose.ui.graphics.toArgb
import com.nativeauto.ui.theme.Light
import com.nativeauto.ui.theme.OledDark
import com.nativeauto.ui.theme.toHex
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory

/**
 * How the map looks in each theme (8c). Dark: OpenFreeMap's dark style recoloured to true
 * black (OLED), roads the lightest thing on it. Light: OpenFreeMap's Positron, a quiet grey
 * style made for data on top, used as is. The route is drawn in the theme's own accent.
 */
internal data class MapLook(
    val dark: Boolean,
    val styleUrl: String,
    /** Land colour, and behind the map until it draws. */
    val background: Int,
    val route: String,
    val routeCasing: String,
    val destination: String,
    /** A trip's start dot in history: the theme's text colour (no green: §11). */
    val start: String,
) {
    companion object {
        val Dark = MapLook(
            dark = true,
            styleUrl = "https://tiles.openfreemap.org/styles/dark",
            background = OledDark.mapBackground.toArgb(),
            route = "#3FC8E0",
            routeCasing = "#0F2A4A",
            destination = "#E5484D",
            start = "#E8EAED",
        )
        val Day = MapLook(
            dark = false,
            styleUrl = "https://tiles.openfreemap.org/styles/positron",
            background = Light.mapBackground.toArgb(),
            route = "#0A86A0",
            routeCasing = "#FFFFFF",
            destination = "#C62828",
            start = "#14171C",
        )

        fun of(dark: Boolean) = if (dark) Dark else Day
    }

    /** 13c: the same look with the route line in [colour] (the rider's route colour, or the accent). */
    fun withRoute(colour: androidx.compose.ui.graphics.Color) = copy(route = colour.toHex())

    /** Recolours the loaded style for the dashboard. Nothing to do for Positron. */
    fun applyTo(getLayer: (String) -> Layer?) {
        if (dark) applyOledPalette(getLayer, background)
    }
}

/**
 * Recolours OpenFreeMap's dark style for an OLED screen: true black land, near-black blocks,
 * and roads lighter than everything around them (the stock style draws roads darker than the
 * land; at a glance from a handlebar mount, roads must stand out). res/raw/map_style_dark.json
 * is the Google twin. Layers missing from a future style version are skipped.
 */
private fun applyOledPalette(getLayer: (String) -> Layer?, background: Int) {
    fun fill(id: String, color: String, outline: String? = null) = getLayer(id)?.setProperties(
        *listOfNotNull(PropertyFactory.fillColor(color), outline?.let(PropertyFactory::fillOutlineColor)).toTypedArray(),
    )
    fun line(id: String, color: String) = getLayer(id)?.setProperties(PropertyFactory.lineColor(color))
    fun text(id: String, color: String) = getLayer(id)?.setProperties(
        PropertyFactory.textColor(color),
        PropertyFactory.textHaloColor(LABEL_HALO),
    )

    getLayer("background")?.setProperties(PropertyFactory.backgroundColor(background))
    fill("landuse_residential", "#06080b")
    fill("landcover_wood", "#06120a"); fill("landuse_park", "#06120a")
    fill("building", "#0e1116", outline = "#06080b")
    fill("water", "#03101b"); line("waterway", "#03101b")

    line("highway_path", "#1c2027")
    line("highway_minor", "#2a2f37")
    line("highway_major_casing", "#000000"); line("highway_major_inner", "#3a414b"); line("highway_major_subtle", "#3a414b")
    line("highway_motorway_casing", "#14171c"); line("highway_motorway_inner", "#4c5662"); line("highway_motorway_subtle", "#4c5662")

    text("highway_name_other", "#8a9099"); text("highway_name_motorway", "#e8eaed")
    text("water_name", "#4e6d82")
    listOf("place_other", "place_suburb", "place_village").forEach { text(it, "#9aa0a6") }
    listOf("place_town", "place_city", "place_city_large").forEach { text(it, "#c4c8cd") }
}

private const val LABEL_HALO = "#000000"
