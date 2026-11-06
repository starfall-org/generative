package org.starfall.generative.shared.ui.icons.fluentui.regular

/*
MIT License

Copyright (c) 2020 Microsoft Corporation

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

val FluentuiSystemIconsShapes: ImageVector
    get() {
        if (_FluentuiSystemIconsShapes != null) return _FluentuiSystemIconsShapes!!

        _FluentuiSystemIconsShapes = ImageVector.Builder(
            name = "shapes",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.Black)
            ) {
                moveTo(8.75f, 3.5f)
                curveTo(5.85051f, 3.5f, 3.5f, 5.85051f, 3.5f, 8.75f)
                curveTo(3.5f, 11.3949f, 5.45578f, 13.5829f, 8f, 13.9468f)
                verticalLineTo(15.4588f)
                curveTo(4.62504f, 15.0857f, 2f, 12.2244f, 2f, 8.75f)
                curveTo(2f, 5.02208f, 5.02208f, 2f, 8.75f, 2f)
                curveTo(12.2244f, 2f, 15.0857f, 4.62504f, 15.4588f, 8f)
                horizontalLineTo(13.9468f)
                curveTo(13.5829f, 5.45578f, 11.3949f, 3.5f, 8.75f, 3.5f)
                close()
                moveTo(12.25f, 9f)
                curveTo(10.4551f, 9f, 9f, 10.4551f, 9f, 12.25f)
                verticalLineTo(18.75f)
                curveTo(9f, 20.5449f, 10.4551f, 22f, 12.25f, 22f)
                horizontalLineTo(18.75f)
                curveTo(20.5449f, 22f, 22f, 20.5449f, 22f, 18.75f)
                verticalLineTo(12.25f)
                curveTo(22f, 10.4551f, 20.5449f, 9f, 18.75f, 9f)
                horizontalLineTo(12.25f)
                close()
                moveTo(10.5f, 12.25f)
                curveTo(10.5f, 11.2835f, 11.2835f, 10.5f, 12.25f, 10.5f)
                horizontalLineTo(18.75f)
                curveTo(19.7165f, 10.5f, 20.5f, 11.2835f, 20.5f, 12.25f)
                verticalLineTo(18.75f)
                curveTo(20.5f, 19.7165f, 19.7165f, 20.5f, 18.75f, 20.5f)
                horizontalLineTo(12.25f)
                curveTo(11.2835f, 20.5f, 10.5f, 19.7165f, 10.5f, 18.75f)
                verticalLineTo(12.25f)
                close()
            }
        }.build()

        return _FluentuiSystemIconsShapes!!
    }

private var _FluentuiSystemIconsShapes: ImageVector? = null
