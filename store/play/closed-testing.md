# Closed testing: the gate to Production

Lightforge Studio lives on a **personal** Play Console account, so Google requires a closed test before
the app can be published to Production:

- at least **12 testers opted in** to the closed test when you apply for production access, and
- those testers must have stayed opted in **continuously for the preceding 14 days**.

Open testing is locked until production access is granted. The Play Console dashboard tracks the
count ("N testers currently opted-in"). Plan for 15–20 testers so a few dropouts do not reset the clock.

## Track setup (Play Console > Test and release > Closed testing > Closed testing - Alpha)

| Setting | Value |
|---|---|
| Release | `lightforge-0.3.0-beta-122.aab` (versionCode 122, upload key SHA-256 `1dbd3d04…ab9c`) |
| Countries | Same as the store listing (all supported) |
| Testers | Google Group (public sign-up) **and** an email list for people added directly |
| Feedback | GitHub Issues: https://github.com/LibreStatic/lightforge/issues; email: support@librestatic.com |

### Adding testers

1. **Google Group** (anyone can join): `lightforge-testers@googlegroups.com`, created
   with "Who can join: Anyone on the web". Its members get access automatically. Publish both links:
   - the group: `https://groups.google.com/g/lightforge-testers`, and
   - the opt-in page: https://play.google.com/apps/testing/com.librestatic.lightforge (the "Join on the web" link on the Testers tab).
2. **Email list** (people you pick): Testers tab > Create email list, add their Google account emails.
   Up to 2,000 addresses per list; the same list can be reused for other apps.

A person only counts once they open the opt-in link and tap "Become a tester". Being in the group or
list alone is not enough.

## It is a paid app: what testers pay

- **Closed test:** testers buy the app at the store price (USD 5 / ARS 2500). Only the internal test
  installs paid apps for free.
- **Refunding testers:** Play Console > Order management > find the order > Refund. Do *not* tick
  "Revoke access", so they keep the app.
- **Promo codes:** Play Console > Grow users > Promotions can generate up to 500 paid-app codes per
  quarter. They are the cleanest way to hand out free copies, but check that redemption works
  while the app is only on a closed track before relying on them (create one code and redeem it
  with a tester account).
- **Do not switch the app to Free for the test.** A free app can never become paid again.
- **Internal testing** (up to 100 people, free install, no review) is useful for close friends but does
  **not** count toward the 12-tester requirement.

## Applying for Production (after 14 days)

Dashboard > "Apply for production". Google asks about the closed test: how testers were recruited, the
feedback received and what changed because of it, and why the app is ready. Keep a short log of the
issues reported during the test (GitHub Issues works) so the answers are concrete.

## Recruiting message

### Español

> **Busco testers para Lightforge Studio (Android)**
>
> Lightforge es una galería privada y sin conexión para bibliotecas enormes de fotos y videos:
> búsqueda por contenido, personas y mascotas reconocidas en el dispositivo, editor de fotos y RAW,
> editor de video con LOG/HDR y estudio de PDF. Sin anuncios, sin cuentas, nada sale del teléfono.
>
> Necesito gente que la use 14 días antes del lanzamiento en Google Play (Android 11 o superior):
> 1. Unite al grupo: https://groups.google.com/g/lightforge-testers
> 2. Activá la prueba: https://play.google.com/apps/testing/com.librestatic.lightforge
> 3. Instalala desde Google Play y quedate en la prueba al menos 14 días.
>
> Es una app paga: te devuelvo el importe (o te paso un código) si me escribís con el mail de tu
> cuenta de Google. Errores y sugerencias: https://github.com/LibreStatic/lightforge/issues o
> support@librestatic.com.

### English

> **Looking for testers for Lightforge Studio (Android)**
>
> Lightforge is a private, offline gallery for huge photo and video libraries: search by what is in
> the photo, on-device people and pet recognition, a photo and RAW editor, a video editor with
> LOG/HDR and a PDF studio. No ads, no accounts, nothing leaves the phone.
>
> I need people to use it for 14 days before the Google Play launch (Android 11 or newer):
> 1. Join the group: https://groups.google.com/g/lightforge-testers
> 2. Opt in: https://play.google.com/apps/testing/com.librestatic.lightforge
> 3. Install it from Google Play and stay in the test for at least 14 days.
>
> It is a paid app: I will refund it (or send you a code) if you email me your Google account
> address. Bugs and ideas: https://github.com/LibreStatic/lightforge/issues or
> support@librestatic.com.
