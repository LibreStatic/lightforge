package com.librestatic.lightforge.core.database

/** Query projection only: rows the timeline shows for one local day (`yyyy-MM-dd`). */
data class TimelineDayCountRow(val day: String, val count: Int)
