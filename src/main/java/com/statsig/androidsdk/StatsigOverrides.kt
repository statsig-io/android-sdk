package com.statsig.androidsdk

import com.google.gson.annotations.SerializedName

data class StatsigOverrides(
  @SerializedName("gates")
  val gates: MutableMap<String, Boolean>,

  @SerializedName("configs")
  val configs: MutableMap<String, Map<String, Any>>,

  @SerializedName("layers")
  val layers: MutableMap<String, Map<String, Any>>
) {
  companion object {
    fun empty(): StatsigOverrides {
      return StatsigOverrides(mutableMapOf(), mutableMapOf(), mutableMapOf())
    }
  }
}