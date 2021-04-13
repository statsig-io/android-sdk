# Statsig Android SDK

## Common Setup

In `build.gradle` include the statsig dependency, directly from the github source (via jitpack).

In your root build.gradle, at the end of repositories, add:

        allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}

Then, add the dependency:

`implementation 'com.github.statsig-io:android-sdk:v1.0.0'`

Finally, run a gradle sync so Intellij/Android Studio recognizes the Statsig library.

For more information on including a jitpack library as a dependency https://jitpack.io/

The SDK is written in Kotlin, but can be used by Android Apps written in either Java OR Kotlin!
## Java

    Statsig.initialize(  
        app,  
        "<CLIENT_SDK_KEY>",  
         this::onStatsigReady,  
         new StatsigUser("<USER_ID_OR_NULL>")
     )

where `onStatsigReady` is a callback, defined like this:

	private void onStatsigReady() {
	    // use your gates and feature configs now!
	    DynamicConfig androidConfig = Statsig.getConfig("android_config");
	    if (androidConfig == null) {  
		    return;  
		}
		String title androidConfig.getValue("title", "Fallback Title");
		
		Statsig.logEvent("test_event", 10.0);
    }
## Kotlin

	Statsig.initialize(  
	    this.application,  
	    "<CLIENT_SDK_KEY>",  
	    StatsigUser("<USER_ID_OR_NULL>"),  
	    ::onStatsigInitialized,  
	)

where `onStatsigInitialized` is a callback, defined like this:

    private fun onStatsigInitialized() {
		String title = Statsig.getConfig("android_config")?.getValue("title", "Fallback Title")

		if (title != null && title.isNotEmpty()) {
		    // use your title
		}
		
		String usingGate = if (Statsig.checkGate("my_gk1")) "true" else "false"
		
		Statsig.logEvent("test_event")
	}
