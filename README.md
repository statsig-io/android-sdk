# Statsig Android SDK

![Release](https://jitpack.io/v/statsig-io/android-sdk.svg)

## Common Setup

In `build.gradle` include the statsig dependency, directly from the github source (via jitpack).

In your root `build.gradle`, at the end of repositories, add:

        allprojects {
		repositories {
			...
			maven { url 'https://jitpack.io' }
		}
	}

Then, add the dependency, where the version is a git tag from this repository:

`implementation 'com.github.statsig-io:android-sdk:v2.0.0'`

Finally, run a gradle sync so Intellij/Android Studio recognizes the Statsig library.

For more information on including a jitpack library as a dependency https://jitpack.io/

The SDK is written in Kotlin, but can be used by Android Apps written in either Java OR Kotlin!
## Java

    import com.statsig.androidsdk.*;

    ...

    Application app = getApplication();
    CompletableFuture future = Statsig.initializeAsync(app, "<CLIENT_SDK_KEY>");
    future.thenApply(this::onStatsigReady);

where `onStatsigReady` is a callback, defined like this:

	private Object onStatsigReady(Object empty) {
	    // use your gates and feature configs now!
	    DynamicConfig androidConfig = Statsig.getConfig("android_config");
	    if (androidConfig == null) {  
		    return;  
		}
		String title androidConfig.getValue("title", "Fallback Title");
		
		Statsig.logEvent("test_event", 10.0);
    }
    
## Kotlin

    import com.statsig.androidsdk.*

    ...

    val callback = object : StatsigCallback {
        override fun onStatsigReady() {
            // check gates/configs and log events
        }
    }

	Statsig.initialize(  
	    this.application,  
	    "<CLIENT_SDK_KEY>",  
	    StatsigUser("<USER_ID_OR_NULL>"),  
	    callback,
	)


## What is Statsig?

Statsig helps you move faster with Feature Gates (Feature Flags) and Dynamic Configs. It also allows you to run A/B tests to validate your new features and understand their impact on your KPIs. If you're new to Statsig, create an account at [statsig.com](https://www.statsig.com).
