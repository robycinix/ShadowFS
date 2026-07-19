# ShadowFS - Resumen

ShadowFS libera espacio en Android moviendo archivos inactivos a tu Raspberry Pi,
NAS o servidor Linux domestico. El telefono conserva un marcador ligero y
restaura el archivo completo cuando lo necesitas.

El objetivo es ofrecer comodidad similar a la nube, pero con control local: sin
suscripcion ShadowFS, sin proveedor cloud obligatorio y autenticacion mTLS entre
telefono y servidor.

## En sintesis

- Mueve fotos, videos, PDF, ZIP y otros archivos elegibles cuando baja el espacio
  disponible.
- Verifica cada subida con SHA-256 antes de modificar el archivo local.
- Mantiene miniaturas o marcadores para que los archivos sigan siendo visibles.
- Restaura archivos completos bajo demanda desde la app.
- Soporta Tailscale para acceso fuera de casa.
- Permite proteger carpetas sensibles de la gestion automatica.

## Seguridad y responsabilidad

ShadowFS no cambia el archivo local sin confirmacion completa del servidor. Aun
asi, no sustituye una estrategia de backup. Pruebalo primero con archivos no
criticos y conserva siempre una copia separada de los datos importantes.
