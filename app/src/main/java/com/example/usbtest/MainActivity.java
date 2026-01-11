package com.example.usbtest;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbConstants;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.widget.Button;
import android.widget.TextView;

import java.util.HashMap;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.example.usbhosttest.USB_PERMISSION";
    private UsbManager usbManager;
    private TextView tvLog;
    private Button btnScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 简易 UI 初始化(若使用 layout xml)
        tvLog = findViewById(R.id.tv_log);
        btnScan = findViewById(R.id.btn_scan);

        tvLog.setMovementMethod(new ScrollingMovementMethod());

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // 注册广播接收器：监听插拔和权限结果
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        btnScan.setOnClickListener(v -> scanDevices());

        log("应用启动。请插入USB设备(OTG)并点击扫描...");
        scanDevices(); // 启动时尝试扫描
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
    }

    private void scanDevices() {
        tvLog.setText(""); // 清屏
        log("正在扫描USB设备...");

        HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
        if (deviceList.isEmpty()) {
            log("未发现设备。请检查OTG开关或连接线。");
            return;
        }

        for (UsbDevice device : deviceList.values()) {
            log("发现设备: " + device.getDeviceName());
            log("VID: " + String.format("0x%04X", device.getVendorId()) + " | PID: " + String.format("0x%04X", device.getProductId()));

            // 检查权限，如果没有权限则申请
            if (!usbManager.hasPermission(device)) {
                log("请求权限中...");
                requestPermission(device);
            } else {
                log("已有权限，读取详细信息...");
                readDeviceDetails(device);
            }
        }
    }

    // 申请USB权限
    private void requestPermission(UsbDevice device) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        // Android 12 (S) 及以上要求明确指定Mutable/Immutable
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent permissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        usbManager.requestPermission(device, permissionIntent);
    }

    // 读取设备接口和端点信息
    private void readDeviceDetails(UsbDevice device) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            log("错误：无法打开设备连接(可能被其他App占用)");
            return;
        }

        // 读取设备基本信息
        // 注意：getProductName 需要 API 21+，且如果设备没权限可能返回null
        log("产品名称: " + device.getProductName());
        log("厂商名称: " + device.getManufacturerName());

        log("---- 接口信息 ----");
        log("接口总数: " + device.getInterfaceCount());

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            log("\n[接口 " + i + "]");
            log("   类别(Class): " + resolveClass(usbInterface.getInterfaceClass()));
            log("   子类(SubClass): " + usbInterface.getInterfaceSubclass());
            log("   端点数: " + usbInterface.getEndpointCount());

            // 遍历端点(Endpoint) - 这是通信的关键
            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(j);
                String direction = (endpoint.getDirection() == UsbConstants.USB_DIR_IN) ? "输入(IN)" : "输出(OUT)";
                String type = resolveEndpointType(endpoint.getType());
                log("   -> 端点 " + j + ": " + direction + " | 类型: " + type + " | 包大小: " + endpoint.getMaxPacketSize());
            }
        }
        connection.close();
        log("\n设备读取完毕。");
    }

    // 广播接收器
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            log("用户已授权!");
                            readDeviceDetails(device);
                        }
                    } else {
                        log("用户拒绝了权限。");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("广播：检测到设备插入");
                scanDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("广播：设备已拔出");
            }
        }
    };


    // 辅助工具：输出日志到屏幕
    private void log(String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");
            // 自动滚到底部
            int offset = tvLog.getLineCount() * tvLog.getLineHeight();
            if (offset > tvLog.getHeight()) {
                tvLog.scrollTo(0, offset - tvLog.getHeight());
            }
        });
    }

    // 辅助工具：解析USB类型代码
    private String resolveClass(int cls) {
        switch (cls) {
            case UsbConstants.USB_CLASS_AUDIO:
                return "Audio(音频)";
            case UsbConstants.USB_CLASS_COMM:
                return "CDC(通信)";
            case UsbConstants.USB_CLASS_HID:
                return "HID(键鼠)";
            case UsbConstants.USB_CLASS_MASS_STORAGE:
                return "Mass Storage (U盘)";
            case UsbConstants.USB_CLASS_PRINTER:
                return "Printer (打印机)";
            case UsbConstants.USB_CLASS_VIDEO:
                return "Video (摄像头)";
            case UsbConstants.USB_CLASS_VENDOR_SPEC:
                return "厂商自定义 (Vendor)";
            default:
                return "代码" + cls;
        }
    }

    private String resolveEndpointType(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "Control (控制)";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "Isochronous (等时/音频)";
            case UsbConstants.USB_ENDPOINT_XFER_BULK:
                return "Bulk (批量/U盘)";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "Interrupt (中断/HID)";
            default:
                return "未知";
        }
    }
}