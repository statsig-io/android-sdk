package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName
import java.util.concurrent.ConcurrentHashMap

data class StatsigOverrides(
  @SerializedName("gates")
  val gates: ConcurrentHashMap<String, Boolean>,

  @SerializedName("configs")
  val configs: ConcurrentHashMap<String, Map<String, Any>>,

  @SerializedName("layers")
  val layers: ConcurrentHashMap<String, Map<String, Any>>
) {
  companion object {
    fun empty(): StatsigOverrides {
      return StatsigOverrides(ConcurrentHashMap(), ConcurrentHashMap(), ConcurrentHashMap())
    }
  }
}