# LiteSend  

*本项目还在开发中，可能会出现bug，还需要进行大量测试*  
*本项目使用了AI进行编程*  

LiteSend 是为 Android 4.0–4.4准备的 LocalSend 兼容客户端，原生，与官方 LocalSend 协议 v2/v2.1 互通。  
接收的文件会保存在 /sdcard/Download/LiteSend  
纯 Java 实现，传输使用 TLS 1.2（P-256 ECDHE + AES-GCM），速度约 0.4–1 MB/s  
若发现不到设备：请确认在同一 Wi-Fi、关闭路由器的 AP 隔离，或使用「手动IP」  
首次启动会生成自签名证书，可能要等待一会。  
本应用暂不支持对端设置 PIN。  



非常感谢DeepSeek-V4.1-Flash，实现了本应用的大部分功能  
2026-11-25
