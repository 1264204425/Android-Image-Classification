package com.baidu.paddle.lite.demo.image_classification;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AppCompatActivity;

import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName(); // 用于标记日志
    public static final int OPEN_GALLERY_REQUEST_CODE = 0; // 用于标记打开相册的请求
    public static final int TAKE_PHOTO_REQUEST_CODE = 1; // 用于标记拍照的请求

    public static final int REQUEST_LOAD_MODEL = 0; // 用于标记加载模型的请求
    public static final int REQUEST_RUN_MODEL = 1; // 用于标记运行模型的请求
    public static final int RESPONSE_LOAD_MODEL_SUCCESSED = 0; // 用于标记加载模型成功的响应
    public static final int RESPONSE_LOAD_MODEL_FAILED = 1; //  用于标记加载模型失败的响应
    public static final int RESPONSE_RUN_MODEL_SUCCESSED = 2; // 用于标记运行模型成功的响应
    public static final int RESPONSE_RUN_MODEL_FAILED = 3;  // 用于标记运行模型失败的响应

    protected ProgressDialog pbLoadModel = null; // 用于显示加载模型的进度条
    protected ProgressDialog pbRunModel = null; // 用于显示运行模型的进度条

    protected Handler receiver = null; // 用于接收来自 worker 线程的消息
    protected Handler sender = null; // 用于向 worker 线程发送消息
    protected HandlerThread worker = null; // 用于加载模型和运行模型的 worker 线程

    // UI components of image classification
    protected TextView tvInputSetting; // 用于显示模型的输入设置
    protected ImageView ivInputImage; // 用于显示模型的输入图片
    protected TextView tvTop1Result; // 用于显示模型的前三个分类结果
    protected TextView tvTop2Result; // 用于显示模型的前三个分类结果
    protected TextView tvTop3Result; // 用于显示模型的前三个分类结果
    protected TextView tvInferenceTime; // 用于显示模型的推理时间
    // protected Switch mSwitch;

    // Model settings of image classification
    protected String modelPath = ""; // 模型路径
    protected String labelPath = ""; // 标签路径
    protected String imagePath = ""; // 测试图片路径
    protected int cpuThreadNum = 1; // CPU 线程数
    protected String cpuPowerMode = ""; // CPU 功耗模式
    protected String inputColorFormat = ""; // 输入图片的颜色格式
    protected long[] inputShape = new long[]{}; // 输入图片的形状
    protected float[] inputMean = new float[]{}; // 输入图片的均值
    protected float[] inputStd = new float[]{}; // 输入图片的标准差
    protected boolean useGpu = false; // 是否使用 GPU

    protected Predictor predictor = new Predictor(); // 用于加载和运行模型的 Predictor

    private static final int MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE = 1; // 用于标记读取外部存储的请求


    @Override
    // 用于创建 Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 检查权限并请求
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
              != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                  new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                  MY_PERMISSIONS_REQUEST_READ_EXTERNAL_STORAGE);
        }

        // 设置按钮跳转到自定义页面
        Button btnMyCustomPage = findViewById(R.id.btn_my_custom_page);
        btnMyCustomPage.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, MyCustomActivity.class);
            startActivity(intent);
        });

        // Clear all setting items to avoid app crashing due to the incorrect settings
        // 清除所有设置项，以避免由于错误的设置而导致应用程序崩溃
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.clear();
        editor.commit();

        // Prepare the worker thread for mode loading and inference
        // 为模式加载和推理准备工作线程
        receiver = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                // msg是来自worker线程的消息
                switch (msg.what) {
                    case RESPONSE_LOAD_MODEL_SUCCESSED:  // 加载模型成功
                        pbLoadModel.dismiss(); // 关闭加载模型的进度条
                        onLoadModelSuccessed(); // 加载模型成功后的操作
                        break;
                    case RESPONSE_LOAD_MODEL_FAILED: // 加载模型失败
                        pbLoadModel.dismiss(); // 关闭加载模型的进度条
                        Toast.makeText(MainActivity.this, "Load model failed!", Toast.LENGTH_SHORT).show(); // 提示加载模型失败
                        onLoadModelFailed(); // 加载模型失败后的操作
                        break;
                    case RESPONSE_RUN_MODEL_SUCCESSED:
                        pbRunModel.dismiss();
                        onRunModelSuccessed();
                        break;
                    case RESPONSE_RUN_MODEL_FAILED:
                        pbRunModel.dismiss();
                        Toast.makeText(MainActivity.this, "Run model failed!", Toast.LENGTH_SHORT).show();
                        onRunModelFailed();
                        break;
                    default:
                        break;
                }
            }
        };

        worker = new HandlerThread("Predictor Worker"); // 创建工作线程
        worker.start(); // 启动工作线程

        // 创建用于向工作线程发送消息的 Handler
        sender = new Handler(worker.getLooper()) {
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case REQUEST_LOAD_MODEL:
                        // 加载模型并重新加载测试图片
                        if (onLoadModel()) {
                            receiver.sendEmptyMessage(RESPONSE_LOAD_MODEL_SUCCESSED);
                        } else {
                            receiver.sendEmptyMessage(RESPONSE_LOAD_MODEL_FAILED);
                        }
                        break;
                    case REQUEST_RUN_MODEL:
                        // Run model if model is loaded
                        if (onRunModel()) {
                            receiver.sendEmptyMessage(RESPONSE_RUN_MODEL_SUCCESSED);
                        } else {
                            receiver.sendEmptyMessage(RESPONSE_RUN_MODEL_FAILED);
                        }
                        break;
                    default:
                        break;
                }
            }
        };

        // 设置UI组件
        tvInputSetting = findViewById(R.id.tv_input_setting);
        ivInputImage = findViewById(R.id.iv_input_image);
        tvTop1Result = findViewById(R.id.tv_top1_result);
        tvTop2Result = findViewById(R.id.tv_top2_result);
        tvTop3Result = findViewById(R.id.tv_top3_result);
        tvInferenceTime = findViewById(R.id.tv_inference_time);
        tvInputSetting.setMovementMethod(ScrollingMovementMethod.getInstance());
        // mSwitch=(Switch) findViewById(R.id.btn_switch);
        // mSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
        //     @Override
        //     public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        //         if(isChecked) {
        //             useGpu = true;
        //         } else {
        //             useGpu = false;
        //         }
        //         if (useGpu) {
        //             modelPath = modelPath.split("/")[0] + "/mobilenet_v1_for_gpu";
        //         } else {
        //             modelPath = modelPath.split("/")[0] + "/mobilenet_v1_for_cpu";
        //         }
        //         tvInputSetting.setText("Model: " + modelPath.substring(modelPath.lastIndexOf("/") + 1) + "\n" + "CPU" +
        //                 " Thread Num: " + Integer.toString(cpuThreadNum) + "\n" + "CPU Power Mode: " + cpuPowerMode + "\n");
        //         tvInputSetting.scrollTo(0, 0);
        //         loadModel();
        //     }
        // });
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean settingsChanged = false;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        String model_path = sharedPreferences.getString(getString(R.string.MODEL_PATH_KEY),
              getString(R.string.MODEL_PATH_DEFAULT));
        String label_path = sharedPreferences.getString(getString(R.string.LABEL_PATH_KEY),
              getString(R.string.LABEL_PATH_DEFAULT));
        String image_path = sharedPreferences.getString(getString(R.string.IMAGE_PATH_KEY),
              getString(R.string.IMAGE_PATH_DEFAULT));
        settingsChanged |= !model_path.equalsIgnoreCase(modelPath);
        settingsChanged |= !label_path.equalsIgnoreCase(labelPath);
        settingsChanged |= !image_path.equalsIgnoreCase(imagePath);
        int cpu_thread_num = Integer.parseInt(sharedPreferences.getString(getString(R.string.CPU_THREAD_NUM_KEY),
              getString(R.string.CPU_THREAD_NUM_DEFAULT)));
        settingsChanged |= cpu_thread_num != cpuThreadNum;
        String cpu_power_mode =
              sharedPreferences.getString(getString(R.string.CPU_POWER_MODE_KEY),
                    getString(R.string.CPU_POWER_MODE_DEFAULT));
        settingsChanged |= !cpu_power_mode.equalsIgnoreCase(cpuPowerMode);
        String input_color_format =
              sharedPreferences.getString(getString(R.string.INPUT_COLOR_FORMAT_KEY),
                    getString(R.string.INPUT_COLOR_FORMAT_DEFAULT));
        settingsChanged |= !input_color_format.equalsIgnoreCase(inputColorFormat);
        long[] input_shape =
              Utils.parseLongsFromString(sharedPreferences.getString(getString(R.string.INPUT_SHAPE_KEY),
                    getString(R.string.INPUT_SHAPE_DEFAULT)), ",");
        float[] input_mean =
              Utils.parseFloatsFromString(sharedPreferences.getString(getString(R.string.INPUT_MEAN_KEY),
                    getString(R.string.INPUT_MEAN_DEFAULT)), ",");
        float[] input_std =
              Utils.parseFloatsFromString(sharedPreferences.getString(getString(R.string.INPUT_STD_KEY)
                    , getString(R.string.INPUT_STD_DEFAULT)), ",");
        settingsChanged |= input_shape.length != inputShape.length;
        settingsChanged |= input_mean.length != inputMean.length;
        settingsChanged |= input_std.length != inputStd.length;
        if (!settingsChanged) {
            for (int i = 0; i < input_shape.length; i++) {
                settingsChanged |= input_shape[i] != inputShape[i];
            }
            for (int i = 0; i < input_mean.length; i++) {
                settingsChanged |= input_mean[i] != inputMean[i];
            }
            for (int i = 0; i < input_std.length; i++) {
                settingsChanged |= input_std[i] != inputStd[i];
            }
        }
        if (settingsChanged) {
            modelPath = model_path;
            labelPath = label_path;
            imagePath = image_path;
            cpuThreadNum = cpu_thread_num;
            cpuPowerMode = cpu_power_mode;
            inputColorFormat = input_color_format;
            inputShape = input_shape;
            inputMean = input_mean;
            inputStd = input_std;
            if (useGpu) {
                modelPath = modelPath.split("/")[0] + "/mobilenet_v1_for_gpu";
            } else {
                modelPath = modelPath.split("/")[0] + "/mobilenet_v1_for_cpu";
            }
            // Update UI
            tvInputSetting.setText("Model: " + modelPath.substring(modelPath.lastIndexOf("/") + 1) + "\n" + "CPU" +
                  " Thread Num: " + Integer.toString(cpuThreadNum) + "\n" + "CPU Power Mode: " + cpuPowerMode + "\n");
            tvInputSetting.scrollTo(0, 0);
            // Reload model if configure has been changed
            loadModel();
        }
    }

    public void loadModel() {
        pbLoadModel = ProgressDialog.show(this, "", "Loading model...", false, false);
        sender.sendEmptyMessage(REQUEST_LOAD_MODEL);
    }

    public void runModel() {
        pbRunModel = ProgressDialog.show(this, "", "Running model...", false, false);
        sender.sendEmptyMessage(REQUEST_RUN_MODEL);
    }

    public boolean onLoadModel() {
        return predictor.init(MainActivity.this, modelPath, labelPath, cpuThreadNum,
              cpuPowerMode,
              inputColorFormat,
              inputShape, inputMean,
              inputStd);
    }

    public boolean onRunModel() {
        return predictor.isLoaded() && predictor.runModel();
    }

    public void onLoadModelSuccessed() {
        // Load test image from path and run model
        try {
            if (imagePath.isEmpty()) {
                return;
            }
            Bitmap image = null;
            // 读取自定义路径的测试图像文件，如果模式路径的第一个字符是'/'，否则读取测试
            if (!imagePath.substring(0, 1).equals("/")) {
                InputStream imageStream = getAssets().open(imagePath);
                image = BitmapFactory.decodeStream(imageStream);
            } else {
                if (!new File(imagePath).exists()) {
                    return;
                }
                image = BitmapFactory.decodeFile(imagePath);
            }
            if (image != null && predictor.isLoaded()) {
                predictor.setInputImage(image);
                runModel();
            }
        } catch (IOException e) {
            Toast.makeText(MainActivity.this, "Load image failed!", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    public void onLoadModelFailed() {
    }

    public void onRunModelSuccessed() {
        // Obtain results and update UI
        tvInferenceTime.setText("Inference time: " + predictor.inferenceTime() + " ms");
        Bitmap inputImage = predictor.inputImage();
        if (inputImage != null) {
            ivInputImage.setImageBitmap(inputImage);
        }
        tvTop1Result.setText(predictor.top1Result());
        tvTop2Result.setText(predictor.top2Result());
        tvTop3Result.setText(predictor.top3Result());
    }

    public void onRunModelFailed() {
    }

    public void onImageChanged(Bitmap image) {
        // Rerun model if users pick test image from gallery or camera
        if (image != null && predictor.isLoaded()) {
            predictor.setInputImage(image);
            runModel();
        }
    }

    public void onSettingsClicked() {
        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_action_options, menu);
        return true;
    }

    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean isLoaded = predictor.isLoaded();
        menu.findItem(R.id.open_gallery).setEnabled(isLoaded);
        menu.findItem(R.id.take_photo).setEnabled(isLoaded);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                break;
            case R.id.open_gallery:
                if (requestAllPermissions()) {
                    openGallery();
                }
                break;
            case R.id.take_photo:
                if (requestAllPermissions()) {
                    takePhoto();
                }
                break;
            case R.id.settings:
                if (requestAllPermissions()) {
                    // Make sure we have SDCard r&w permissions to load model from SDCard
                    onSettingsClicked();
                }
                break;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults[0] != PackageManager.PERMISSION_GRANTED || grantResults[1] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permission Denied", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean requestAllPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
              != PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,
              Manifest.permission.CAMERA)
              != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.CAMERA},
                  0);
            return false;
        }
        return true;
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_PICK, null);
        intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        startActivityForResult(intent, OPEN_GALLERY_REQUEST_CODE);
    }

    private void takePhoto() {
        Intent takePhotoIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePhotoIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePhotoIntent, TAKE_PHOTO_REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null) {
            switch (requestCode) {
                case OPEN_GALLERY_REQUEST_CODE:
                    try {
                        ContentResolver resolver = getContentResolver();
                        Uri uri = data.getData();
                        Bitmap image = MediaStore.Images.Media.getBitmap(resolver, uri);
                        String[] proj = {MediaStore.Images.Media.DATA};
                        Cursor cursor = managedQuery(uri, proj, null, null, null);
                        cursor.moveToFirst();
                        onImageChanged(image);
                    } catch (IOException e) {
                        Log.e(TAG, e.toString());
                    }
                    break;
                case TAKE_PHOTO_REQUEST_CODE:
                    Bundle extras = data.getExtras();
                    Bitmap image = (Bitmap) extras.get("data");
                    onImageChanged(image);
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (predictor != null) {
            predictor.releaseModel();
        }
        worker.quit();
        super.onDestroy();
    }
}
