s#^fun NativeAutoTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {#fun NativeAutoTheme(mode: ThemeMode = ThemeMode.DARK, accent: Color = LegacyAccent.dark, content: @Composable () -> Unit) {#
s#^    val colors = mode.palette()#    val colors = mode.palette().withAccent(accent)#
