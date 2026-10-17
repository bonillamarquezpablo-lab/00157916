# Política de seguridad de Mínimo

## Cómo comunicar una vulnerabilidad

No publiques claves API, tokens, archivos de firma, datos personales ni instrucciones de explotación en incidencias públicas.

Utiliza la función privada **Security → Report a vulnerability** del repositorio, si está disponible, o contacta privadamente con la persona propietaria del repositorio. Incluye el componente afectado, la versión, los pasos mínimos para reproducir el problema y el impacto observado. No incluyas credenciales reales.

## Gestión de secretos

- Las claves de proveedor se introducen en Ajustes y se cifran en el dispositivo con Android Keystore; no deben incluirse en el código fuente.
- Los archivos locales `.env`, `google-services.json`, almacenes de claves y archivos de propiedades de credenciales no deben incorporarse al control de versiones.
- Si una credencial se expone, revócala o rótala en el proveedor cuanto antes. Borrar el texto de un commit no elimina la exposición del historial.
- La clave de firma de la aplicación debe permanecer en un secreto de GitHub y nunca aparecer en artefactos de CI, registros o código.

## Controles automatizados

El flujo de pull requests ejecuta pruebas sin acceso a secretos de firma. La publicación del APK está separada y solo se ejecuta desde `main`. Las acciones de terceros se fijan a SHA completos y Dependabot revisa semanalmente las dependencias Gradle y las acciones de GitHub.

## Alcance

Esta política documenta medidas preventivas, no una certificación formal ni una garantía de ausencia total de vulnerabilidades. Las compilaciones deben pasar las pruebas y la verificación de firma antes de distribuirse.
