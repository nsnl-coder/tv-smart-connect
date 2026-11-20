# TV Smart Connect

Dùng iPhone điều khiển Fire TV để xem web phim, kể cả khi iPhone và TV ở hai mạng khác nhau.

```
iPhone (trang remote) ──▶ server (Render) ──▶ app trên Fire TV (trình duyệt toàn màn hình)
```

- `server/`: relay WebSocket (Node.js) và trang remote cho iPhone (`server/public/index.html`). File APK được phục vụ tại `/tv.apk`.
- `tv-app/`: app Android cho Fire TV, gồm WebView, script điều khiển video `assets/tvsc.js` và con trỏ chuột.

## Cài lên Fire TV

1. **Settings → My Fire TV → About**: bấm 7 lần vào tên thiết bị để bật Developer Options.
2. Cài app **Downloader** từ Appstore. Vào **Developer Options → Install unknown apps** và bật quyền cho Downloader.
3. Mở Downloader, gõ `https://tv-smart-connect.onrender.com/tv.apk`, rồi bấm **Install**.

## Dùng

1. Mở app **TV Smart Connect** trên TV, màn hình sẽ hiện mã QR.
2. Quét mã QR bằng Camera iPhone để mở trang remote. Có thể thêm trang này vào Màn hình chính để mở nhanh.
3. Dán link trang phim rồi bấm **Mở trên TV**. TV mở trang, tự phát video và phóng to toàn màn hình.

## Build lại APK

```sh
cd tv-app
./gradlew assembleRelease -PserverUrl=https://tv-smart-connect.onrender.com
cp app/build/outputs/apk/release/app-release.apk ../server/public/tv-smart-connect.apk
```
