# "Who is in the Building" Live Attendance Roster Implementation Plan

Add a live, scrollable **"Who is in the Building"** attendance roster section right under the Workshop Attendance card on the main dashboard. This section will display everyone who has checked in, showing their captured selfie thumbnail, attendee ID, matched name, tag, and check-in timestamp.

## User Review Required

> [!IMPORTANT]
> - **Dashboard Roster Card**: Displays a horizontal or vertical scrollable list of recent check-ins queried directly from the local `AttendanceDatabase`.
> - **Visual Details**: Each entry shows the captured selfie thumbnail, matched worker/subscriber name & tag, attendee ID, and check-in time.

## Proposed Changes

### [MODIFY] [MainActivity.kt](file:///C:/Users/BiGBuG/AndroidStudioProjects/Jack/app/src/main/java/com/example/jack/MainActivity.kt)
- **`WhoIsInTheBuildingCard` Composable**:
  - Queries `AttendanceDatabase` for records.
  - Renders scrollable cards with selfie thumbnails, attendee names, tags, and timestamps.
- **`ESP32SerialScreen` Integration**:
  - Place `WhoIsInTheBuildingCard` directly below the Workshop Attendance action card.

## Verification Plan

### Automated Tests
- Gradle build verification (`app:assembleDebug`).

### Manual Verification
- Mark attendance with a registered user/worker (with selfie capture), return to the main dashboard, and verify the user appears instantly in the "Who is in the Building" scroll view with their selfie and timestamp.
