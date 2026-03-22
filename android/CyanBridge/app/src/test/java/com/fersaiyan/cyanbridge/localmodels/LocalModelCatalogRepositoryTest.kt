package com.fersaiyan.cyanbridge.localmodels

import com.fersaiyan.cyanbridge.localmodels.catalog.LocalModelCatalogRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCatalogRepositoryTest {
    @Test
    fun catalog_contains_required_starter_ids() {
        val ids = LocalModelCatalogRepository.curatedModels.map { it.id }.toSet()
        assertTrue(ids.contains("qwen2.5-0.5b-instruct-q4"))
        assertTrue(ids.contains("qwen2.5-1.5b-instruct-q4"))
        assertTrue(ids.contains("qwen3.5-0.8b-q4"))
        assertTrue(ids.contains("gemma-3-1b-it-q4"))
    }

    @Test
    fun every_catalog_entry_has_local_gguf_contract() {
        LocalModelCatalogRepository.curatedModels.forEach { entry ->
            assertEquals("local", entry.providerType)
            assertEquals("llama", entry.engine)
            assertEquals("gguf", entry.format)
            assertTrue(entry.expectedFilename.endsWith(".gguf"))
            assertTrue(entry.contextSizeDefault >= 2048)
        }
    }

    @Test
    fun can_find_model_by_id() {
        val model = LocalModelCatalogRepository.findById("qwen2.5-0.5b-instruct-q4")
        assertNotNull(model)
        assertEquals("qwen", model?.family)
    }
}
