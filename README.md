# תחבורה ציבורית (BusTimes)

יישום לוחות זמנים של התחבורה הציבורית בישראל לטלפונים כשרים (J2ME, MIDP 2.0, CLDC 1.1), בלי אינטרנט:
מתי יוצא האוטובוס הבא מהתחנה, כל התחנות והשעות של כל קו, ותכנון מסלול מנקודה לנקודה כולל החלפות והליכה.

הנתונים נמצאים בקובץ נפרד, `bus.dat`, שנבנה מקובצי ה-GTFS של משרד התחבורה. הקובץ של משרד התחבורה מכסה כחודש
קדימה, ולכן צריך לבנות את `bus.dat` מחדש בערך פעם בחודש. המאגר הזה מכיל את כל מה שצריך כדי לעשות את זה,
וגם כדי לבנות את היישום עצמו.

## מה יש כאן
- `src/bs` – קוד המקור של היישום (Java).
- `stub` – הצהרות של ממשקי JSR-75 (קבצים) ו-JSR-179 (מיקום), לצורך הידור בלבד.
- `tools/mkbus.py` – בניית `bus.dat` מקובץ ה-GTFS.
- `tools` – שאר כלי הבנייה: גופנים (`mkfonts.py`, `nikud.py`), אייקון, אריזת ה-JAR, והוספת StackMap (`cldc.py`).
- `fonts/f` – הגופנים המוכנים.
- `build.ps1` – בניית ה-JAR.

## עדכון לוחות הזמנים (בלי לגעת בקוד)
1. מורידים את קובץ ה-GTFS של משרד התחבורה:
   `https://gtfs.mot.gov.il/gtfsfiles/israel-public-transportation.zip`
2. מתקינים Python 3 (אין צורך בחבילות נוספות).
3. מריצים (כשתי דקות):
   `python -I tools/mkbus.py israel-public-transportation.zip bus.dat`
4. מעתיקים את `bus.dat` לכרטיס הזיכרון במקום הקובץ הישן.

מבנה הקובץ `bus.dat` מתועד בראש `tools/mkbus.py`.

חיפוש כתובת ביישום משתמש בקובץ `israel.map` של יישום המפות (אם הוא נמצא בכרטיס).

## בניית היישום
דרושים: Java (JRE 8 ומעלה), מהדר ECJ (`ecj.jar`), קובץ JAR עם ממשקי MIDP 2.0 / CLDC 1.1 להידור מולו
(למשל `freej2me.jar`), ו-Python 3 עם Pillow.

```
powershell -File build.ps1 -Ecj lib\ecj.jar -Midp lib\midp.jar
```

התוצאה: `BusTimes.jar` ו-`BusTimes.jad`. עם `-En` נבנית גם המהדורה באנגלית.

## רישיון ומקורות
- הקוד: רישיון MIT (ראו `LICENSE`).
- הנתונים: משרד התחבורה והבטיחות בדרכים, קובצי GTFS פתוחים.
- הגופנים שבתיקייה `fonts/f` נוצרו מגופני Segoe UI של Microsoft. מי שרוצה גופן אחר משנה את `tools/mkfonts.py`.

---

**English:** an offline public-transport timetable MIDlet (J2ME) for Israel. `tools/mkbus.py` builds the data file
from the Ministry of Transport GTFS feed (it must be rebuilt about once a month). Code: MIT.
