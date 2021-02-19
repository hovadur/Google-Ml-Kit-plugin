package com.b.biradar.google_ml_kit;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.SuccessContinuation;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

//Class to handle the method calls
public class MlKitMethodCallHandler implements MethodChannel.MethodCallHandler {
    private final Context applicationContext;
    //To store detector instances that receive [InputImage] as input.
    Map<String, ApiDetectorInterface> detectorMap = new HashMap<String, ApiDetectorInterface>();
    //To store detector instances that receive inputn
    Map<String, Object> exceptionDetectors = new HashMap<String, Object>();

    public MlKitMethodCallHandler(Context applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case "startBarcodeScanner":
            case "startPoseDetector":
            case "startImageLabelDetector":
            case "startMlDigitalInkRecognizer":
            case "manageInkModels":
            case "startTextDetector":
                handleDetection(call, result);
                break;
            case "closeBarcodeScanner":
            case "closePoseDetector":
            case "closeImageLabelDetector":
            case "closeMlDigitalInkRecognizer":
            case "closeTextDetector":
                closeDetector(call, result);
                break;
            default:
                result.notImplemented();
        }
    }

    //Function to deal with method calls requesting to process an image or other information
    //Checks the method call request and then directs to perform the specific task and returns the result through method channel.
    //Throws an error if failed to create an instance of detector or to complete the detection task.
    private void handleDetection(MethodCall call, MethodChannel.Result result) {
       //Get the parameters passed along with method call.
        Map<String, Object> options = call.argument("options");

        //If method call is to manage the language models.
        if (call.method.equals("manageInkModels")) {
            manageLanguageModel(call, result);
            return;
        } else if (call.method.equals("startMlDigitalInkRecognizer")) {
            //Retrieve the instance if already created.
            MlDigitalInkRecogniser recogniser = (MlDigitalInkRecogniser) exceptionDetectors.get(call.method.substring(5));
            if (recogniser == null) {
                //Create an instance if not present in the hashMap.
                recogniser = MlDigitalInkRecogniser.Instance((String) call.argument("modelTag"), result);
            }
            if (recogniser != null) {
                recogniser.handleDetection(result, (List<Map<String, Object>>) call.argument("points"));
            } else {
                result.error("Failed to create model identifier", null, null);
            }
            return;
        }

        if(call.method.equals("startPoseDetector")){
            Log.e("Pose detector Log",options.toString());
            if(call.argument("mode").equals("stream")){
                MlPoseDetector mlPoseDetector = new MlPoseDetector(options);
                mlPoseDetector.fromByteBuffer((Map<String,Object>) call.arguments(),result);
            }
            return;
        }

        ApiDetectorInterface detector = detectorMap.get(call.method.substring(5));
        InputImage inputImage;
        try {
            inputImage = getInputImage((Map<String, Object>) call.argument("imageData"), result);
        } catch (Exception e) {
            Log.e("ImageError", "Getting Image failed");
            e.printStackTrace();
            result.error("imageInputError", e.toString(), null);
            return;
        }


        //If the method is called to detect pose.
        if(call.method.equals("startPoseDetector")){
                if(detector==null) detector = new MlPoseDetector(options);
                if(options.get("mode").equals("static")){
                    detector.handleDetection(inputImage, result);
                }else{
                    MlPoseDetector mlPoseDetector = new MlPoseDetector(options);
                    mlPoseDetector.fromByteBuffer(options,result);
                }
                return;
        }
        if (detector == null) {
            switch (call.method) {
                case "startBarcodeScanner":
                    detector = new BarcodeDetector((List<Integer>) call.argument("formats"));
                    break;
                case "startImageLabelDetector":
                    detector = new ImageLabelDetector(options);
                    break;
                case "startPoseDetector":
                    detector = new MlPoseDetector(options);
                    break;
                case "startTextDetector":
            }

            detectorMap.put(call.method.substring(5), detector);
        }


        assert detector != null;
        detector.handleDetection(inputImage, result);

    }

    //Closes the detector instances if already present else throws error.
    private void closeDetector(MethodCall call, MethodChannel.Result result) {
        final ApiDetectorInterface detector = detectorMap.get(call.method.substring(5));
        String error = String.format(call.method.substring(5), "Has been closed or not been created yet");
        Log.e("Closed Detector", detectorMap.toString());
        if (call.method.equals("closeMlDigitalInkRecognizer")) {
            final MlDigitalInkRecogniser recogniser = (MlDigitalInkRecogniser) exceptionDetectors.get(call.method.substring(5));
            if (recogniser == null) {
                throw new IllegalArgumentException(error);
            }
            try {
                recogniser.closeDetector();
                result.success(null);
                exceptionDetectors.remove(call.method.substring(5));
                return;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (detector == null) {
            throw new IllegalArgumentException(error);
        }

        try {
            detector.closeDetector();
            result.success(null);
        } catch (IOException e) {
            result.error("Could not close", e.getMessage(), null);
        } finally {
            detectorMap.remove(call.method.substring(5));
        }
    }

    //Returns an [InputImage] from the image data received
    private InputImage getInputImage(Map<String, Object> imageData, MethodChannel.Result result) {
        //Differentiates whether the image data is a path for a image file or contains image data in form of bytes
        String model = (String) imageData.get("type");
        InputImage inputImage;
        if (model.equals("file")) {
            try {
                inputImage = InputImage.fromFilePath(applicationContext, Uri.fromFile(new File(((String) imageData.get("path")))));
                return inputImage;
            } catch (IOException e) {
                Log.e("ImageError", "Getting Image failed");
                e.printStackTrace();
                result.error("imageInputError", e.toString(), null);
                return null;
            }
        } else if (model.equals("bytes")) {
            Map<String, Object> metaData = (Map<String, Object>) imageData.get("metadata");
            inputImage = InputImage.fromByteArray((byte[]) imageData.get("bytes"),
                    (int) (double) metaData.get("width"),
                    (int) (double) metaData.get("height"),
                    (int) metaData.get("rotation"),
                    InputImage.IMAGE_FORMAT_NV21);
            return inputImage;

        } else {
            new IOException("Error occurred");
            return null;
        }
    }

    //Function to download and delete language models required for digital ink recognition api
    //Also checks if a model is already downloaded or not.
    private void manageLanguageModel(MethodCall call, MethodChannel.Result result) {
        String task = call.argument("task");
        ModelDownloadManager modelDownloadManager = ModelDownloadManager.Instance((String) call.argument("modelTag"), result);
        if (modelDownloadManager != null) {
            assert task != null;
            switch (task) {
                case "check":
                    if (modelDownloadManager.isModelDownloaded()) {
                        Log.e("Model Download Details", "Model is Dowwnloaded");
                        result.success("exists");
                    }
                    if (!modelDownloadManager.isModelDownloaded()) {
                        Log.e("Model Download Details", "Model is not Dowwnloaded");
                        result.success("not exists");
                    }
                    if (modelDownloadManager.isModelDownloaded() == null) {
                        Log.e("verification Failed ", "Error in running the is DownLoad method");
                        result.error("Verify Failed", "Error in running the is DownLoad method", null);
                    }
                    break;
                case "download":
                    String downloadResult = modelDownloadManager.downloadModel();
                    if (downloadResult.equals("fail")) {
                        result.error("Download Failed", null, null);
                    } else {
                        result.success(downloadResult);
                    }
                    break;
                case "delete":
                    String deleteResult = modelDownloadManager.deleteModel();
                    if (deleteResult.equals("fail")) {
                        result.error("Download Failed", null, null);
                    } else {
                        result.success(deleteResult);
                    }
                    break;
            }
        }
    }
}
