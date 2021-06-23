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

`implementation 'com.github.statsig-io:android-sdk:v3.0.0'`

Finally, run a gradle sync so Intellij/Android Studio recognizes the Statsig library.

For more information on including a jitpack library as a dependency https://jitpack.io/

The SDK is written in Kotlin, but can be used by Android Apps written in either Java OR Kotlin!
## Java

    import com.statsig.androidsdk.*;

    ...

    // Implement the initialize callback interface
    public class MainActivity extends AppCompatActivity implements IStatsigInitializeCallback {

        private Handler mHandler = null

        ...
        protected void onCreate(Bundle savedInstanceState) {
            mHandler = new android.os.Handler(Looper.getMainLooper());
            Application app = getApplication();
            StatsigUser user = new StatsigUser(<USER_ID>);
            // initialization will happen on a background thread, but post back via the handler
            Statsig.initializeAsync(app, <CLIENT_SDK_KEY>, user, this);
        }

        @Override
        public void onStatsigInitialize() {
	        // use your gates and feature configs now!
	        DynamicConfig androidConfig = Statsig.getConfig("android_config");
	        if (androidConfig == null) {  
		        return;  
	        }
	        String title androidConfig.getValue("title", "Fallback Title");
		
	        Statsig.logEvent("test_event", 10.0);
        }

        @Override
        public Handler getStatsigHandler() {
            // return a handler for Statsig to callback to the correct thread
            return mHandler;
        }

        @Override
        public void onStatsigUpdateUser() {
            // handle user updates
        }
    
## Kotlin

    import com.statsig.androidsdk.*

    ...

    val initialize = CoroutineScope(Dispatchers.Default).async {
	    Statsig.initialize(  
	        this.application,  
	        "<CLIENT_SDK_KEY>",  
	        StatsigUser("<USER_ID_OR_NULL>"),
	    )
    }
    initialize.await()

    val featureOn = Statsig.checkGate("<GATE_NAME>")


## What is Statsig?

Statsig helps you move faster with Feature Gates (Feature Flags) and Dynamic Configs. It also allows you to run A/B tests to validate your new features and understand their impact on your KPIs. If you're new to Statsig, create an account at [statsig.com](https://www.statsig.com).
