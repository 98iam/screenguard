Set WshShell = CreateObject("WScript.Shell")
WshShell.Run """C:\Users\hzran\AppData\Local\Programs\Python\Python311\pythonw.exe"" """ & CreateObject("Scripting.FileSystemObject").GetParentFolderName(WScript.ScriptFullName) & "\app\main.py""", 0, False
