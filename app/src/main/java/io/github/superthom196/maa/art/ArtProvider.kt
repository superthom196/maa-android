package io.github.superthom196.maa.art

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor

/** WS-B: serves [ArtUris] content URIs to Android Auto / the phone UI. Stub. */
class ArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
    override fun getType(uri: Uri): String = "image/jpeg"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
