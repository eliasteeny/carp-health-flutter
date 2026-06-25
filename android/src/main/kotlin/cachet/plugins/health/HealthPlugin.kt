package cachet.plugins.health

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.NonNull
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import com.google.android.gms.auth.api.signin.GoogleSignIn
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry.ActivityResultListener
import kotlinx.coroutines.*
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

const val GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 1111
const val HEALTH_CONNECT_RESULT_CODE = 16969
const val MMOLL_2_MGDL = 18.0 // 1 mmoll= 18 mgdl

/**
 * Main Flutter plugin class for Health Connect integration. Manages plugin lifecycle, method
 * channel communication, permission handling, and coordinates between Flutter and Android Health
 * Connect APIs.
 */
class HealthPlugin(private var channel: MethodChannel? = null) :
        MethodCallHandler, ActivityResultListener, Result, ActivityAware, FlutterPlugin {

    private var mResult: Result? = null
    private var handler: Handler? = null
    private var activity: Activity? = null
    private var context: Context? = null
    private var healthConnectRequestPermissionsLauncher: ActivityResultLauncher<Set<String>>? = null
    private lateinit var healthConnectClient: HealthConnectClient
    private lateinit var scope: CoroutineScope
    private var isReplySubmitted = false

    // Helper classes
    private lateinit var dataReader: HealthDataReader
    private lateinit var dataWriter: HealthDataWriter
    private lateinit var dataOperations: HealthDataOperations
    private lateinit var dataConverter: HealthDataConverter
    private lateinit var dataChanges: HealthDataChanges

    // Health Connect availability
    private var healthConnectAvailable = false
    private var healthConnectStatus = HealthConnectClient.SDK_UNAVAILABLE

    private var useGoogleFit = false
    private var threadPoolExecutor: ExecutorService? = null

    companion object {
        const val CHANNEL_NAME = "flutter_health"
    }

    /**
     * Initializes the plugin when attached to the Flutter engine. Sets up method channel, checks
     * Health Connect availability, and initializes helper classes.
     *
     * @param flutterPluginBinding Plugin binding providing access to Flutter engine resources
     */
    override fun onAttachedToEngine(
            @NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding
    ) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_NAME)
        channel?.setMethodCallHandler(this)
        context = flutterPluginBinding.applicationContext
        handler = Handler(context!!.mainLooper)
        threadPoolExecutor = Executors.newFixedThreadPool(4)
        checkAvailability()
        if (healthConnectAvailable) {
            healthConnectClient =
                    HealthConnectClient.getOrCreate(flutterPluginBinding.applicationContext)
            initializeHelpers()
        }
    }

    /**
     * Cleans up resources when plugin is detached from Flutter engine. Cancels coroutines and
     * nullifies references to prevent memory leaks.
     *
     * @param binding Plugin binding (unused in cleanup)
     */
    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = null
        activity = null
        threadPoolExecutor!!.shutdown()
        threadPoolExecutor = null
        scope.cancel()

    }

    override fun success(p0: Any?) {
        handler?.post { mResult?.success(p0) }
    }

    override fun notImplemented() {
        handler?.post { mResult?.notImplemented() }
    }

    override fun error(
            errorCode: String,
            errorMessage: String?,
            errorDetails: Any?,
    ) {
        handler?.post { mResult?.error(errorCode, errorMessage, errorDetails) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i("FLUTTER_HEALTH", "Access Granted!")
                mResult?.success(true)
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Log.i("FLUTTER_HEALTH", "Access Denied!")
                mResult?.success(false)
            }
        }
        return false
    }

    /**
     * Handles method calls from Flutter and routes them to appropriate handler classes. Central
     * dispatcher for all Health Connect operations including permissions, data reading, writing,
     * and deletion.
     *
     * @param call Method call from Flutter containing method name and arguments
     * @param result Result callback to return data or status to Flutter
     */
    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            // SDK and Installation
            "installHealthConnect" -> installHealthConnect(call, result)
            "useGoogleFit" -> useGoogleFit(call, result)
            "getHealthConnectSdkStatus" -> {
                checkAvailability()
                if (healthConnectAvailable && !(this::dataOperations.isInitialized)) {
                    healthConnectClient = HealthConnectClient.getOrCreate(context!!)
                    initializeHelpers()
                }
                result.success(healthConnectStatus)
            }

            // Permissions
            "hasPermissions" -> dataOperations.hasPermissions(call, result, useGoogleFit, context)
            "requestAuthorization" -> requestAuthorization(call, result)
            "forceRequestAuthorization" -> forceRequestAuthorization(call,result)
            "revokePermissions" -> dataOperations.revokePermissions(call, result, useGoogleFit, context,activity )

            // History permissions
            "isHealthDataHistoryAvailable" ->
                    dataOperations.isHealthDataHistoryAvailable(call, result)
            "isHealthDataHistoryAuthorized" ->
                    dataOperations.isHealthDataHistoryAuthorized(call, result)
            "requestHealthDataHistoryAuthorization" ->
                    requestHealthDataHistoryAuthorization(call, result)

            // Background permissions
            "isHealthDataInBackgroundAvailable" ->
                    dataOperations.isHealthDataInBackgroundAvailable(call, result)
            "isHealthDataInBackgroundAuthorized" ->
                    dataOperations.isHealthDataInBackgroundAuthorized(call, result)
            "requestHealthDataInBackgroundAuthorization" ->
                    requestHealthDataInBackgroundAuthorization(call, result)
            "isSkinTemperatureAvailable" ->
                    dataOperations.isSkinTemperatureAvailable(call, result)

            // Reading data
            "getData" -> dataReader.getData(call, result, useGoogleFit, context, threadPoolExecutor )
            "getDataByUUID" -> dataReader.getDataByUUID(call, result)
            "getIntervalData" -> dataReader.getIntervalData(call, result)
            "getAggregateData" -> dataReader.getAggregateData(call, result)
            "getTotalStepsInInterval" -> dataReader.getTotalStepsInInterval(call, result)
            "getChangesToken" -> dataChanges.getChangesToken(call, result)
            "getChanges" -> dataChanges.getChanges(call, result)

            // Writing data
            "writeData" -> dataWriter.writeData(call, result,useGoogleFit, context)
            "writeWorkoutData" -> dataWriter.writeWorkoutData(call, result)
            "writeBloodPressure" -> dataWriter.writeBloodPressure(call, result)
            "writeBloodOxygen" -> dataWriter.writeBloodOxygen(call, result,useGoogleFit, context)
            "writeMenstruationFlow" -> dataWriter.writeMenstruationFlow(call, result,useGoogleFit, context)
            "writeMeal" -> dataWriter.writeMeal(call, result,useGoogleFit, context)
            "writeActivityIntensity" -> dataWriter.writeActivityIntensity(call, result)
            "startWorkoutRoute" -> dataWriter.startWorkoutRoute(result)
            "insertWorkoutRouteData" -> dataWriter.insertWorkoutRouteData(call, result)
            "finishWorkoutRoute" -> dataWriter.finishWorkoutRoute(call, result)
            "discardWorkoutRoute" -> dataWriter.discardWorkoutRoute(call, result)
            // TODO: Add support for multiple speed for iOS as well
            // "writeMultipleSpeed" -> dataWriter.writeMultipleSpeedData(call, result)

            // Deleting data
            "delete" -> dataOperations.deleteData(call, result, useGoogleFit, context)
            "deleteMeals" -> dataOperations.deleteMeals(call, result, useGoogleFit, context)
            "deleteByUUID" -> dataOperations.deleteByUUID(call, result)
            "deleteByClientRecordId" -> dataOperations.deleteByClientRecordId(call, result)
            "isGoogleFitAvailable" -> isGoogleFitAvailable(call, result)
            else -> result.notImplemented()
        }
    }

    /**
     * Called when activity is attached to the plugin. Sets up permission request launcher and
     * activity result handling.
     *
     * @param binding Activity plugin binding providing activity context
     */
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        if (channel == null) {
            return
        }
        binding.addActivityResultListener(this)
        activity = binding.activity

        val requestPermissionActivityContract =
                PermissionController.createRequestPermissionResultContract()

        healthConnectRequestPermissionsLauncher =
                (activity as ComponentActivity).registerForActivityResult(
                        requestPermissionActivityContract
                ) { granted -> onHealthConnectPermissionCallback(granted) }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    /**
     * Called when activity is detached from plugin. Cleans up activity-specific resources and
     * permission launchers.
     */
    override fun onDetachedFromActivity() {
        if (channel == null) {
            return
        }
        activity = null
        healthConnectRequestPermissionsLauncher = null
    }

    /**
     * Checks Health Connect availability and SDK status on the current device. Determines if Health
     * Connect is installed and accessible.
     */
    private fun checkAvailability() {
        healthConnectStatus = HealthConnectClient.getSdkStatus(context!!)
        healthConnectAvailable = healthConnectStatus == HealthConnectClient.SDK_AVAILABLE
    }

    /**
     * Initializes helper classes for data operations after Health Connect client is ready. Creates
     * instances of reader, writer, operations, and converter classes.
     */
    private fun initializeHelpers() {
        dataConverter = HealthDataConverter()
        dataReader = HealthDataReader(healthConnectClient, scope, context!!, dataConverter)
        dataWriter = HealthDataWriter(healthConnectClient, scope)
        dataOperations =
                HealthDataOperations(
                        healthConnectClient,
                        scope,
                        healthConnectStatus,
                        healthConnectAvailable
                )
        dataChanges = HealthDataChanges(healthConnectClient, scope, context!!, dataConverter)
    }

    /**
     * Launches Health Connect installation flow via Google Play Store. Directs users to install
     * Health Connect when it's not available.
     *
     * @param call Method call from Flutter (unused)
     * @param result Flutter result callback
     */
    private fun installHealthConnect(call: MethodCall, result: Result) {
        val uriString =
                "market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"
        context!!.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setPackage("com.android.vending")
                    data = android.net.Uri.parse(uriString)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra("overlay", true)
                    putExtra("callerId", context!!.packageName)
                }
        )
        result.success(null)
    }

    /**
     * Handles permission request results from Health Connect permission dialog. Called when user
     * responds to permission request, updates Flutter with result.
     *
     * @param permissionGranted Set of permission strings that were granted
     */
    private fun onHealthConnectPermissionCallback(permissionGranted: Set<String>) {
        if (!isReplySubmitted) {
            if (permissionGranted.isEmpty()) {
                mResult?.success(false)
                Log.i(
                        "FLUTTER_HEALTH",
                        "Health Connect permissions were not granted! Make sure to declare the required permissions in the AndroidManifest.xml file."
                )
            } else {
                mResult?.success(true)
                Log.i(
                        "FLUTTER_HEALTH",
                        "${permissionGranted.size} Health Connect permissions were granted!"
                )
                Log.i("FLUTTER_HEALTH", "Permissions granted: $permissionGranted")
            }
            isReplySubmitted = true
        }
    }

    /**
     * Initiates Health Connect permission request flow. Prepares permission list and launches
     * system permission dialog.
     *
     * @param call Method call containing permission types and access levels
     * @param result Flutter result callback for permission request outcome
     */
    private fun requestAuthorization(call: MethodCall, result: Result) {
        if (context == null) {
            result.success(false)
            return
        }

        mResult = result

        if(useGoogleFit){



            val optionsToRegister =  HealthConstants.callToGoogleFitHealthTypes(call)

            // Set to false due to bug described in
            // https://github.com/cph-cachet/flutter-plugins/issues/640#issuecomment-1366830132
            val isGranted = false

            // If not granted then ask for permission
            if (!isGranted && activity != null) {
                GoogleSignIn.requestPermissions(
                    activity!!,
                    GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                    GoogleSignIn.getLastSignedInAccount(context!!),
                    optionsToRegister,
                )
            } else { // / Permission already granted
                result?.success(true)
            }

        }else {
            if (healthConnectRequestPermissionsLauncher == null) {
                result.success(false)
                Log.i("FLUTTER_HEALTH", "Permission launcher not found")
                return
            }

            // Store the result to be called in onHealthConnectPermissionCallback

            isReplySubmitted = false

            val permList = dataOperations.preparePermissionsList(call)
            if (permList == null) {
                result.success(false)
                return
            }

            healthConnectRequestPermissionsLauncher!!.launch(permList.toSet())
        }


    }

    private fun forceRequestAuthorization(call: MethodCall, result: Result) {
        if (context == null) {
            result.success(false)
            return
        }



        if(useGoogleFit) {

            if (activity == null) {

                result.success(false)
                return
            }

            val optionsToRegister =  HealthConstants.callToGoogleFitHealthTypes(call)
            mResult = result


            GoogleSignIn.requestPermissions(
                activity!!,
                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(activity!!),
                optionsToRegister
            )
        }else {
            if (healthConnectRequestPermissionsLauncher == null) {
                result.success(false)
                Log.i("FLUTTER_HEALTH", "Permission launcher not found")
                return
            }

            // Store the result to be called in onHealthConnectPermissionCallback

            isReplySubmitted = false

            val permList = dataOperations.preparePermissionsList(call)
            if (permList == null) {
                result.success(false)
                return
            }

            healthConnectRequestPermissionsLauncher!!.launch(permList.toSet())
        }



    }

    /**
     * Requests specific permission for accessing health data history. Launches permission dialog
     * for historical data access capability.
     *
     * @param call Method call from Flutter (unused)
     * @param result Flutter result callback for permission request outcome
     */
    private fun requestHealthDataHistoryAuthorization(call: MethodCall, result: Result) {
        if (context == null || healthConnectRequestPermissionsLauncher == null) {
            result.success(false)
            Log.i("FLUTTER_HEALTH", "Permission launcher not found")
            return
        }

        mResult = result
        isReplySubmitted = false
        healthConnectRequestPermissionsLauncher!!.launch(
                setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        )
    }

    /**
     * Requests specific permission for background health data access. Launches permission dialog
     * for background data reading capability.
     *
     * @param call Method call from Flutter (unused)
     * @param result Flutter result callback for permission request outcome
     */
    private fun requestHealthDataInBackgroundAuthorization(call: MethodCall, result: Result) {
        if (context == null || healthConnectRequestPermissionsLauncher == null) {
            result.success(false)
            Log.i("FLUTTER_HEALTH", "Permission launcher not found")
            return
        }

        mResult = result
        isReplySubmitted = false
        healthConnectRequestPermissionsLauncher!!.launch(
                setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        )
    }

    private fun useGoogleFit(call: MethodCall, result: Result) {

        val status = call.argument<Boolean>("status")

        if(status == null) {
            Log.w("FLUTTER_HEALTH::ERROR", "Missing status for setting Google Fit")
            result.success(false)
            return;
        }

        useGoogleFit = status
        result.success(null)
    }

   private  fun isGoogleFitAvailable(call: MethodCall, result: Result) {
        try {
            context!!.packageManager.getPackageInfo("com.google.android.apps.fitness", PackageManager.GET_ACTIVITIES)
            result.success(true)

        } catch (e: Exception) {
            result.success(false)

        }
    }


}
