# Auto Register Shift

Ứng dụng Android chạy hoàn toàn cục bộ, dùng `AccessibilityService` để hỗ trợ người dùng đăng ký ca trên thiết bị của chính họ. Ứng dụng không có quyền Internet, không tải ảnh màn hình/dữ liệu lên máy chủ, không cần root và không dùng ADB sau khi cài.

> **Giới hạn an toàn:** ứng dụng dừng khi nhận diện CAPTCHA, OTP hoặc bước xác minh danh tính. Dự án không có mã vượt xác minh, né phát hiện, giả lập hành vi ngẫu nhiên hay đọc/lưu mật khẩu. Hãy kiểm tra điều khoản sử dụng của ứng dụng mục tiêu trước khi bật tự động hóa.

## Công nghệ

- Kotlin, Jetpack Compose, Material 3
- MVVM/Clean Architecture đơn giản theo các lớp `ui`, `automation`, `service`, `data`, `model`
- Android Gradle Plugin 8.7.3, Gradle 8.9, Java 17
- Min SDK 26 (Android 8.0), Compile/Target SDK 35
- DataStore Preferences
- Kotlin Coroutines, một automation job duy nhất

## Chức năng chính

- Chọn package từ danh sách ứng dụng có launcher hoặc nhập package thủ công.
- Tìm phần tử theo text/content description, đi lên node cha clickable, sau đó mới dùng tọa độ dự phòng.
- Nhận diện thẻ ca bằng giờ 24h, ví dụ `17:00–21:00`.
- Máy trạng thái hữu hạn, timeout, debounce click, giới hạn click/refresh mỗi phút.
- Cooldown và lịch sử trạng thái ca để tránh click trùng.
- Foreground service với notification Pause/Stop.
- Nút nổi Start/Pause/Stop, trạng thái và bộ đếm.
- Overlay kéo dấu `⊕` để lưu tọa độ theo tỉ lệ màn hình.
- Nhật ký cục bộ: lọc, sao chép, xóa và xuất TXT.
- Dừng click khi màn hình tắt/khóa, đổi ứng dụng, sai màn hình hoặc mất Accessibility.

## Cấu trúc

```text
app/src/main/java/com/autoregistershift/
├── MainActivity.kt
├── AutoRegisterApplication.kt
├── automation/       # Engine, FSM, node/gesture/result detector, safety limits
├── data/             # Settings, log và shift history dùng DataStore
├── model/            # AppSettings, CoordinatePoint, ShiftInfo, LogEntry
├── service/          # Accessibility, foreground, floating/capture overlay
├── ui/               # Main, Settings, Coordinate Setup, Log
└── util/             # Regex giờ, đổi tọa độ, kiểm tra package
```

## Mở và build bằng Android Studio

1. Cài Android Studio có JDK 17 và Android SDK Platform 35.
2. Chọn **Open** và mở thư mục chứa file `settings.gradle.kts`.
3. Chờ Gradle Sync hoàn tất.
4. Chọn một điện thoại Android 8.0 trở lên rồi Run, hoặc chọn **Build > Build APK(s)**.

Build bằng terminal:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

Trên Windows PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

APK đầu ra:

- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release cài thử: `app/build/outputs/apk/release/app-release.apk`

Bản `release` của dự án mẫu đang ký bằng debug keystore để có thể cài kiểm thử và tạo đúng file `app-release.apk`. Trước khi phân phối, hãy tạo keystore riêng trong Android Studio (**Build > Generate Signed Bundle / APK**) và thay `signingConfig` trong `app/build.gradle.kts`. Không commit keystore hoặc mật khẩu.

## Cấp quyền lần đầu

1. Mở **Auto Register Shift**.
2. Nhấn **Cấp quyền Trợ năng**.
3. Trong phần Trợ năng, chọn **Auto Register Shift – Trợ năng**, đọc mô tả rồi bật.
4. Quay lại, nhấn **Cấp quyền nút nổi** và cho phép hiển thị trên ứng dụng khác.
5. Android 13 trở lên: nhấn **Cấp quyền thông báo** để notification của foreground service hiển thị đầy đủ.

Ứng dụng chỉ khai báo Trợ năng, foreground service, overlay, notification và rung. Không yêu cầu danh bạ, SMS, vị trí, bộ nhớ hoặc Internet.

## Chọn và cấu hình ứng dụng mục tiêu

1. Mở **Cài đặt**.
2. Nhấn **Chọn từ ứng dụng đã cài** hoặc nhập package, ví dụ `com.example.shiftapp`.
3. Sửa các nhóm chuỗi nhận diện. Mỗi dòng là một chuỗi độc lập; không cần chỉ dùng một cụm từ.
4. Điều chỉnh timing và giới hạn an toàn nếu giao diện mục tiêu tải chậm.
5. Nhấn **Lưu**, rồi dùng **Kiểm tra** ở màn hình chính.

Nên cung cấp ảnh/video của bốn trạng thái để tinh chỉnh chính xác các chuỗi:

- Danh sách chưa có ca.
- Danh sách có ca.
- Trang chi tiết.
- Thông báo đăng ký thành công.

## Cấu hình tọa độ

Tọa độ chỉ là phương án dự phòng sau Accessibility text/content description.

1. Cấp quyền nút nổi và chọn package mục tiêu.
2. Mở **Điểm click**.
3. Chọn điểm, nhấn **Đặt**. Ứng dụng mục tiêu sẽ được mở và một overlay xuất hiện.
4. Kéo dấu `⊕` tới vị trí mong muốn rồi nhấn **Lưu**.
5. Dùng **Thử** khi ứng dụng mục tiêu đang ở foreground. Click thử bị từ chối nếu package hiện tại không đúng.
6. Dùng **Đặt lại** để quay về giá trị mặc định.

Ứng dụng lưu `xRatio = x / screenWidth` và `yRatio = y / screenHeight`, sau đó tính lại tọa độ thật theo kích thước màn hình hiện tại.

## Chạy thử an toàn

1. Đặt `maxRegistrations = 1`, bật **Dừng sau khi đăng ký thành công** và dùng timing dài hơn khi thử lần đầu.
2. Mở đúng màn hình danh sách ca của ứng dụng mục tiêu.
3. Quay lại Auto Register Shift và nhấn **Bắt đầu**, sau đó mở lại ứng dụng mục tiêu.
4. Quan sát notification/nút nổi. Nút **Stop** hủy automation job và ngăn mọi click kế tiếp.
5. Mở **Nhật ký** để kiểm tra từng chuyển trạng thái.

Khi người dùng đổi ứng dụng, khóa màn hình hoặc gặp giao diện hệ thống, engine chuyển sang chờ và không click. Gesture đã được Android nhận trước đúng thời điểm nhấn Stop không thể bị hệ điều hành thu hồi, nhưng callback chờ và mọi gesture/click tiếp theo đều bị hủy.

## Kiểm thử

Unit test bao phủ:

- Chuyển trạng thái và timeout.
- Đổi tọa độ tỉ lệ.
- Regex giờ 24h.
- Cooldown/tránh trùng.
- Retry và stop token khẩn cấp.
- Cây Accessibility giả cho: không có ca, một/nhiều ca, nút đăng ký, ca đầy, thành công, lỗi mạng, loading kéo dài và đổi package.

Chạy:

```bash
./gradlew testDebugUnitTest
```

## Ghi chú tương thích

- Một số ứng dụng dựng UI bằng canvas/WebView và không cung cấp cây Accessibility hữu ích; khi đó cần cấu hình điểm dự phòng.
- Text thực tế có thể khác theo phiên bản/ngôn ngữ của ứng dụng mục tiêu; hãy cập nhật danh sách nhận diện.
- Chính sách Android/nhà sản xuất có thể dừng service khi tối ưu pin quá mạnh. Foreground notification giúp phiên chạy minh bạch nhưng không cố né cơ chế hệ thống.
