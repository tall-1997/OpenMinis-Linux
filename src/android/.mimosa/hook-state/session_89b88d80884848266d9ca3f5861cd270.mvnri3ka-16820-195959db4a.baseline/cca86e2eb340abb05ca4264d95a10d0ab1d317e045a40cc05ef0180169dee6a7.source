package com.openminis.app.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryParseTest {

    @Test
    fun parseSkipsIncompleteAndKeepsTools() {
        val json = """
            {
              "version": 2,
              "plugins": [
                {"id": "no_url", "name": "Broken", "tools": [{"name": "x"}]},
                {
                  "id": "open_meteo_weather",
                  "name": "Open-Meteo",
                  "description": "Weather",
                  "base_url": "https://api.open-meteo.com",
                  "auth_type": "none",
                  "tools": [
                    {
                      "name": "get_forecast",
                      "summary": "Forecast",
                      "method": "GET",
                      "path": "/v1/forecast",
                      "params": [
                        {"name": "latitude", "required": true, "type": "number"},
                        {"name": "longitude", "required": true, "type": "number"}
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val plugins = PluginRegistry.parse(json)
        assertEquals(1, plugins.size)
        assertEquals("open_meteo_weather", plugins[0].id)
        assertEquals("https://api.open-meteo.com", plugins[0].baseUrl)
        assertEquals(1, plugins[0].tools.size)
        assertEquals("get_forecast", plugins[0].tools[0].name)
        assertTrue(plugins[0].tools[0].params.any { it.name == "latitude" && it.required })
        assertEquals("online_open_meteo_weather__get_forecast", OnlineApiTool.toolName(plugins[0].id, plugins[0].tools[0].name))
        assertTrue(OnlineApiTool.isOnline("online_open_meteo_weather__get_forecast"))
    }

    @Test
    fun parseEmptyOnGarbage() {
        assertTrue(PluginRegistry.parse("not-json").isEmpty())
        assertTrue(PluginRegistry.parse("{}").isEmpty())
    }
}
