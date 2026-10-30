# Up2TV

## Platform

android

## Stack

Kotlin 原生 Android 电视应用、NanoHTTPD 局域网服务、无框架浏览器上传页。上传端不安装配套应用，不使用云服务。

## Users

在家庭局域网内，用手机、平板或电脑向 Android 电视上传 APK 的用户。

## Product Purpose

电视开启接收，其他设备扫码或输入地址上传 APK，电视用户通过遥控器确认并调用系统安装器。

## Capabilities and Constraints

- 不限制品牌或型号；需要 Android APK 支持和系统安装权限。
- 可连续保存多个 APK，不支持 XAPK、APKM、拆分包、静默安装。
- 单次接收使用临时凭证；仅在接收界面可见时提供服务。
- HTTP 仅用于可信局域网，不提供加密传输或公网访问。
- 首次安装需要 U 盘或设备现有安装入口。
- 目标电视的遥控器操作、系统授权与安装行为需要真机验证。

## Accessibility & Inclusion

电视端全程支持方向键、确认键和返回键。浏览器页面支持键盘、屏幕阅读器和窄屏；错误信息提供恢复操作。
