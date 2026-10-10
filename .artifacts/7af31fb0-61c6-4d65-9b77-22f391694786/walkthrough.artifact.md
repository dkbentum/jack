# Walkthrough - "Who is in the Building" Live Attendance Roster

Successfully added the **"Who is in the Building"** live scrollable attendance roster directly on the main dashboard right under the Workshop Attendance card.

## Changes

### [MODIFY] [MainActivity.kt](file:///C:/Users/BiGBuG/AndroidStudioProjects/Jack/app/src/main/java/com/example/jack/MainActivity.kt)
- **`WhoIsInTheBuildingCard` Composable**:
  - Queries local `AttendanceDatabase` for check-in records.
  - Displays a header with total checked-in count ("X Checked In").
  - Renders a horizontal `LazyRow` scrollable list of attendee cards showing:
    - Captured selfie thumbnail (or avatar placeholder).
    - Registered worker/subscriber name and color-coded tag (e.g. Worker, Subscriber).
    - Check-in timestamp (HH:mm:ss).
- **Dashboard Integration**:
  - Placed `WhoIsInTheBuildingCard` immediately below the **Mark Attendance** action card on the main screen.

## Verification Results

### Automated Tests
- Gradle build (`app:assembleDebug`) completed successfully without errors.
