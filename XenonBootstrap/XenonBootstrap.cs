using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Windows.Forms;

internal static class XenonBootstrap
{
    private const string PayloadMagic = "XENONPAYLOADV1!!";
    private const string BundleDirectoryName = "Xenon.bundle";
    private const string LauncherRelativePath = "Xenon\\Xenon.exe";
    private const string PayloadStampFileName = ".payload.sha256";
    private const string ExtractOnlyArgument = "--bootstrap-extract-only";
    private const string PrintLauncherArgument = "--bootstrap-print-launcher";
    private static readonly Encoding PayloadEncoding = Encoding.ASCII;

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            string executablePath = GetExecutablePath();
            PayloadFooter footer = PayloadFooter.Read(executablePath);
            string executableDirectory = Path.GetDirectoryName(executablePath);
            string bundleDirectory = Path.Combine(executableDirectory, BundleDirectoryName);
            string launcherPath = EnsureBundle(executablePath, footer, bundleDirectory);

            bool extractOnly = ContainsArgument(args, ExtractOnlyArgument);
            bool printLauncher = ContainsArgument(args, PrintLauncherArgument);
            string[] forwardedArguments = FilterArguments(args);

            if (printLauncher)
            {
                Console.Out.WriteLine(launcherPath);
            }

            if (extractOnly)
            {
                return 0;
            }

            LaunchLauncher(launcherPath, forwardedArguments);
            return 0;
        }
        catch (Exception exception)
        {
            MessageBox.Show(
                exception.Message,
                "Xenon Bootstrap",
                MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return 1;
        }
    }

    private static string GetExecutablePath()
    {
        string executablePath = Application.ExecutablePath;
        if (!string.IsNullOrEmpty(executablePath))
        {
            return Path.GetFullPath(executablePath);
        }

        using (Process process = Process.GetCurrentProcess())
        {
            if (process.MainModule == null || string.IsNullOrEmpty(process.MainModule.FileName))
            {
                throw new InvalidOperationException("Unable to resolve bootstrap executable path.");
            }

            return Path.GetFullPath(process.MainModule.FileName);
        }
    }

    private static bool ContainsArgument(string[] args, string target)
    {
        for (int i = 0; i < args.Length; i++)
        {
            if (string.Equals(args[i], target, StringComparison.Ordinal))
            {
                return true;
            }
        }

        return false;
    }

    private static string[] FilterArguments(string[] args)
    {
        List<string> filtered = new List<string>();
        for (int i = 0; i < args.Length; i++)
        {
            string argument = args[i];
            if (string.Equals(argument, ExtractOnlyArgument, StringComparison.Ordinal) ||
                string.Equals(argument, PrintLauncherArgument, StringComparison.Ordinal))
            {
                continue;
            }

            filtered.Add(argument);
        }

        return filtered.ToArray();
    }

    private static string EnsureBundle(string executablePath, PayloadFooter footer, string bundleDirectory)
    {
        string launcherPath = Path.Combine(bundleDirectory, LauncherRelativePath);
        string mutexName = "Local\\XenonBootstrap_" + footer.PayloadHashHex.Substring(0, 16);
        bool hasHandle = false;

        using (Mutex mutex = new Mutex(false, mutexName))
        {
            try
            {
                hasHandle = mutex.WaitOne(TimeSpan.FromMinutes(5));
                if (!hasHandle)
                {
                    throw new IOException("Timed out while waiting for the Xenon bundle lock.");
                }

                if (!IsBundleValid(bundleDirectory, launcherPath, footer.PayloadHashHex))
                {
                    ExtractBundle(executablePath, footer, bundleDirectory);
                }
            }
            finally
            {
                if (hasHandle)
                {
                    mutex.ReleaseMutex();
                }
            }
        }

        if (!File.Exists(launcherPath))
        {
            throw new FileNotFoundException("Extracted Xenon launcher was not found.", launcherPath);
        }

        return launcherPath;
    }

    private static bool IsBundleValid(string bundleDirectory, string launcherPath, string expectedHash)
    {
        if (!Directory.Exists(bundleDirectory) || !File.Exists(launcherPath))
        {
            return false;
        }

        string stampPath = Path.Combine(bundleDirectory, PayloadStampFileName);
        if (!File.Exists(stampPath))
        {
            return false;
        }

        string actualHash = File.ReadAllText(stampPath, PayloadEncoding).Trim();
        return string.Equals(actualHash, expectedHash, StringComparison.OrdinalIgnoreCase);
    }

    private static void ExtractBundle(string executablePath, PayloadFooter footer, string bundleDirectory)
    {
        string temporaryDirectory = bundleDirectory + ".tmp-" + Guid.NewGuid().ToString("N");
        string backupDirectory = bundleDirectory + ".old";

        Directory.CreateDirectory(temporaryDirectory);
        try
        {
            using (FileStream executableStream = new FileStream(executablePath, FileMode.Open, FileAccess.Read, FileShare.Read))
            using (BoundedStream payloadStream = new BoundedStream(executableStream, footer.PayloadOffset, footer.PayloadLength))
            using (ZipArchive archive = new ZipArchive(payloadStream, ZipArchiveMode.Read))
            {
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    ExtractEntry(entry, temporaryDirectory);
                }
            }

            File.WriteAllText(Path.Combine(temporaryDirectory, PayloadStampFileName), footer.PayloadHashHex, PayloadEncoding);

            if (Directory.Exists(backupDirectory))
            {
                Directory.Delete(backupDirectory, true);
            }

            if (Directory.Exists(bundleDirectory))
            {
                Directory.Move(bundleDirectory, backupDirectory);
            }

            Directory.Move(temporaryDirectory, bundleDirectory);

            if (Directory.Exists(backupDirectory))
            {
                Directory.Delete(backupDirectory, true);
            }
        }
        catch
        {
            TryDeleteDirectory(temporaryDirectory);

            if (!Directory.Exists(bundleDirectory) && Directory.Exists(backupDirectory))
            {
                Directory.Move(backupDirectory, bundleDirectory);
            }

            throw;
        }
    }

    private static void ExtractEntry(ZipArchiveEntry entry, string rootDirectory)
    {
        string rootFullPath = Path.GetFullPath(rootDirectory);
        if (!rootFullPath.EndsWith(Path.DirectorySeparatorChar.ToString(), StringComparison.Ordinal))
        {
            rootFullPath += Path.DirectorySeparatorChar;
        }

        string destinationPath = Path.GetFullPath(Path.Combine(rootDirectory, entry.FullName));
        if (!destinationPath.StartsWith(rootFullPath, StringComparison.OrdinalIgnoreCase))
        {
            throw new IOException("Zip entry escapes extraction directory: " + entry.FullName);
        }

        bool isDirectory = entry.FullName.EndsWith("/", StringComparison.Ordinal) ||
                           entry.FullName.EndsWith("\\", StringComparison.Ordinal);

        if (isDirectory)
        {
            Directory.CreateDirectory(destinationPath);
            return;
        }

        string parentDirectory = Path.GetDirectoryName(destinationPath);
        if (!string.IsNullOrEmpty(parentDirectory))
        {
            Directory.CreateDirectory(parentDirectory);
        }

        using (Stream input = entry.Open())
        using (FileStream output = new FileStream(destinationPath, FileMode.Create, FileAccess.Write, FileShare.None))
        {
            input.CopyTo(output);
        }
    }

    private static void LaunchLauncher(string launcherPath, string[] arguments)
    {
        ProcessStartInfo startInfo = new ProcessStartInfo
        {
            FileName = launcherPath,
            WorkingDirectory = Path.GetDirectoryName(launcherPath),
            UseShellExecute = false,
            Arguments = BuildArgumentString(arguments)
        };

        Process process = Process.Start(startInfo);
        if (process == null)
        {
            throw new IOException("Failed to start extracted Xenon launcher.");
        }
    }

    private static string BuildArgumentString(string[] arguments)
    {
        StringBuilder commandLine = new StringBuilder();
        for (int i = 0; i < arguments.Length; i++)
        {
            if (i > 0)
            {
                commandLine.Append(' ');
            }

            commandLine.Append(QuoteArgument(arguments[i]));
        }

        return commandLine.ToString();
    }

    private static string QuoteArgument(string argument)
    {
        if (string.IsNullOrEmpty(argument))
        {
            return "\"\"";
        }

        bool needsQuotes = false;
        for (int i = 0; i < argument.Length; i++)
        {
            char ch = argument[i];
            if (char.IsWhiteSpace(ch) || ch == '"')
            {
                needsQuotes = true;
                break;
            }
        }

        if (!needsQuotes)
        {
            return argument;
        }

        StringBuilder builder = new StringBuilder();
        builder.Append('"');

        int backslashCount = 0;
        for (int i = 0; i < argument.Length; i++)
        {
            char ch = argument[i];
            if (ch == '\\')
            {
                backslashCount++;
                continue;
            }

            if (ch == '"')
            {
                builder.Append('\\', backslashCount * 2 + 1);
                builder.Append('"');
                backslashCount = 0;
                continue;
            }

            if (backslashCount > 0)
            {
                builder.Append('\\', backslashCount);
                backslashCount = 0;
            }

            builder.Append(ch);
        }

        if (backslashCount > 0)
        {
            builder.Append('\\', backslashCount * 2);
        }

        builder.Append('"');
        return builder.ToString();
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path))
            {
                Directory.Delete(path, true);
            }
        }
        catch
        {
        }
    }

    private sealed class PayloadFooter
    {
        private const int HashLength = 32;
        private const int FooterSize = 16 + 8 + HashLength;
        private readonly byte[] payloadHash;

        private PayloadFooter(long payloadOffset, long payloadLength, byte[] payloadHashBytes)
        {
            PayloadOffset = payloadOffset;
            PayloadLength = payloadLength;
            payloadHash = payloadHashBytes;
        }

        public long PayloadOffset { get; private set; }

        public long PayloadLength { get; private set; }

        public string PayloadHashHex
        {
            get
            {
                StringBuilder builder = new StringBuilder(payloadHash.Length * 2);
                for (int i = 0; i < payloadHash.Length; i++)
                {
                    builder.Append(payloadHash[i].ToString("x2"));
                }

                return builder.ToString();
            }
        }

        public static PayloadFooter Read(string executablePath)
        {
            using (FileStream stream = new FileStream(executablePath, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                if (stream.Length < FooterSize)
                {
                    throw new IOException("Bootstrap executable does not contain an embedded payload footer.");
                }

                stream.Seek(-FooterSize, SeekOrigin.End);
                byte[] footerBytes = new byte[FooterSize];
                ReadExactly(stream, footerBytes, 0, footerBytes.Length);

                string magic = PayloadEncoding.GetString(footerBytes, 0, 16);
                if (!string.Equals(magic, PayloadMagic, StringComparison.Ordinal))
                {
                    throw new IOException("Bootstrap executable payload marker was not found.");
                }

                long payloadLength = BitConverter.ToInt64(footerBytes, 16);
                if (payloadLength <= 0)
                {
                    throw new IOException("Embedded payload length is invalid.");
                }

                long payloadOffset = stream.Length - FooterSize - payloadLength;
                if (payloadOffset < 0)
                {
                    throw new IOException("Embedded payload offset is invalid.");
                }

                byte[] payloadHash = new byte[HashLength];
                Buffer.BlockCopy(footerBytes, 24, payloadHash, 0, HashLength);
                return new PayloadFooter(payloadOffset, payloadLength, payloadHash);
            }
        }

        private static void ReadExactly(Stream stream, byte[] buffer, int offset, int count)
        {
            while (count > 0)
            {
                int read = stream.Read(buffer, offset, count);
                if (read <= 0)
                {
                    throw new EndOfStreamException("Unexpected end of bootstrap executable while reading payload footer.");
                }

                offset += read;
                count -= read;
            }
        }
    }

    private sealed class BoundedStream : Stream
    {
        private readonly Stream baseStream;
        private readonly long start;
        private readonly long length;
        private long position;

        public BoundedStream(Stream baseStream, long start, long length)
        {
            this.baseStream = baseStream;
            this.start = start;
            this.length = length;
            position = 0;
            this.baseStream.Seek(start, SeekOrigin.Begin);
        }

        public override bool CanRead
        {
            get { return true; }
        }

        public override bool CanSeek
        {
            get { return true; }
        }

        public override bool CanWrite
        {
            get { return false; }
        }

        public override long Length
        {
            get { return length; }
        }

        public override long Position
        {
            get { return position; }
            set { Seek(value, SeekOrigin.Begin); }
        }

        public override void Flush()
        {
        }

        public override int Read(byte[] buffer, int offset, int count)
        {
            long remaining = length - position;
            if (remaining <= 0)
            {
                return 0;
            }

            if (count > remaining)
            {
                count = (int)remaining;
            }

            int read = baseStream.Read(buffer, offset, count);
            position += read;
            return read;
        }

        public override long Seek(long offset, SeekOrigin origin)
        {
            long target;
            switch (origin)
            {
                case SeekOrigin.Begin:
                    target = offset;
                    break;
                case SeekOrigin.Current:
                    target = position + offset;
                    break;
                case SeekOrigin.End:
                    target = length + offset;
                    break;
                default:
                    throw new ArgumentOutOfRangeException("origin");
            }

            if (target < 0 || target > length)
            {
                throw new ArgumentOutOfRangeException("offset");
            }

            baseStream.Seek(start + target, SeekOrigin.Begin);
            position = target;
            return position;
        }

        public override void SetLength(long value)
        {
            throw new NotSupportedException();
        }

        public override void Write(byte[] buffer, int offset, int count)
        {
            throw new NotSupportedException();
        }
    }
}
