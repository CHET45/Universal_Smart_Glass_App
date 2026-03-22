package com.fersaiyan.cyanbridge.notes

import android.content.Context
import com.fersaiyan.cyanbridge.ui.MyApplication

/**
 * Minimal service locator so unit tests can override NotesRepository.
 */
object NotesServiceLocator {
    @Volatile
    var overrideRepository: NotesRepository? = null

    fun notesRepository(context: Context): NotesRepository {
        return overrideRepository ?: MyApplication.notesRepository
    }
}
