package com.ai.playtv.model.bean.local.source

/** Null fields represent preparation still pending. */
data class StoredSearchData(val pinyin: String? = null, val initials: String? = null, val restricted: Boolean? = null)
