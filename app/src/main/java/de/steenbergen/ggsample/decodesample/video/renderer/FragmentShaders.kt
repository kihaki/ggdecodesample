package de.steenbergen.ggsample.decodesample.video.renderer


object FragmentShaders {
    val alphaBlend = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord; // maybe makes this a property of GLTexturesRenderer
        
        uniform samplerExternalOES background;
        uniform samplerExternalOES effect;
        uniform samplerExternalOES alpha;
        
        //varying mediump float text_alpha_out;
        void main() {
          vec4 backColor = texture2D(background, vTextureCoord);
          vec4 effectColor = texture2D(effect, vTextureCoord);
          vec4 alphaColor = texture2D(alpha, vTextureCoord);
          
          gl_FragColor = vec4(backColor.rgb, 1.0) + vec4(effectColor.rgb, alphaColor.r);
        } 
    """.trimIndent()
    val alphaBlendByteBuffer = """
        precision mediump float;
        varying vec2 vTextureCoord; // maybe makes this a property of GLTexturesRenderer
        
        uniform sampler2D background;
        uniform sampler2D effect;
        uniform sampler2D alpha;
        
        //varying mediump float text_alpha_out;
        void main() {
          vec4 backColor = texture2D(background, vTextureCoord);
          vec4 effectColor = texture2D(effect, vTextureCoord);
          vec4 alphaColor = texture2D(alpha, vTextureCoord);
          
          gl_FragColor = vec4(backColor.rgb, 1.0) + vec4(effectColor.rgb, alphaColor.r);
        } 
    """.trimIndent()
}
