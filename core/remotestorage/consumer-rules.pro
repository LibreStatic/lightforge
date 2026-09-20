# MBassador dispatches SMB lifecycle events through annotated handlers, including shutdown.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep @interface net.engio.mbassy.listener.Handler
-keep @interface net.engio.mbassy.listener.Listener
-keep @interface net.engio.mbassy.listener.Filter
-keep @interface net.engio.mbassy.listener.Enveloped
-keepclassmembers class com.hierynomus.smbj.** {
    @net.engio.mbassy.listener.Handler <methods>;
}
# SSHJ's optional PEM parser checks this interface by name before using the bundled parser.
-keep,allowoptimization interface org.bouncycastle.openssl.PEMDecryptor { *; }

# SMBJ Android config excludes desktop Kerberos; our explicit password-only contract uses NTLM.
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
# MBassador EL filters are optional desktop-only; SMB uses concrete @Handler event callbacks.
-dontwarn javax.el.BeanELResolver
-dontwarn javax.el.ELContext
-dontwarn javax.el.ELResolver
-dontwarn javax.el.ExpressionFactory
-dontwarn javax.el.FunctionMapper
-dontwarn javax.el.ValueExpression
-dontwarn javax.el.VariableMapper

# Handler.handlerInvocation defaults to this class; SubscriptionFactory obtains this exact
# public constructor reflectively before SMB opens its first socket (MBassador 1.3.2).
-keepclassmembers class net.engio.mbassy.dispatch.ReflectiveHandlerInvocation {
    public <init>(net.engio.mbassy.subscription.SubscriptionContext);
}
