#!/usr/bin/env python3
"""JavaByte native bridge: runs a sample's Windows DLL inside a CPU emulator.

JavaByte interprets Java bytecode but cannot run machine code. This helper fills that gap for
JNI libraries. It loads a DLL into Speakeasy (Mandiant's Windows emulator, built on the Unicorn
CPU emulator) and gives it a JNI environment whose every function is answered here or forwarded
to JavaByte. The DLL's code never runs on the real processor, and the Windows API calls it makes
are simulated and reported.

Protocol: one JSON object per line. JavaByte sends requests on stdin and reads stdout.

    request   {"op": "load", "path": ...}            -> {"result": {"exports": {...}}}
              {"op": "call", "address": n, "args": [{"t": "I", "v": 1}, ...], "ret": "I"}
                                                      -> {"result": {"v": ...}}
              {"op": "shutdown"}
    callback  {"callback": name, ...}                 (helper -> JavaByte, which answers {"v": ...})
    event     {"event": {...}}                        (helper -> JavaByte, no answer)

While a request is being served the helper may send any number of callbacks and events before
the final result. Diagnostics go to stderr, never stdout.

Requires:  pip install speakeasy-emulator   (on Python 3.12+ also: pip install "setuptools<81")
"""
import base64
import json
import struct
import sys
import warnings

warnings.filterwarnings("ignore")  # the Unicorn binding prints deprecation noise on import

import speakeasy  # noqa: E402

# JNI function table, in specification order. The index of a name is its slot in JNIEnv.
JNI_FUNCTIONS = """
    reserved0 reserved1 reserved2 reserved3 GetVersion DefineClass FindClass FromReflectedMethod
    FromReflectedField ToReflectedMethod GetSuperclass IsAssignableFrom ToReflectedField Throw ThrowNew
    ExceptionOccurred ExceptionDescribe ExceptionClear FatalError PushLocalFrame PopLocalFrame NewGlobalRef
    DeleteGlobalRef DeleteLocalRef IsSameObject NewLocalRef EnsureLocalCapacity AllocObject NewObject
    NewObjectV NewObjectA GetObjectClass IsInstanceOf GetMethodID CallObjectMethod CallObjectMethodV
    CallObjectMethodA CallBooleanMethod CallBooleanMethodV CallBooleanMethodA CallByteMethod CallByteMethodV
    CallByteMethodA CallCharMethod CallCharMethodV CallCharMethodA CallShortMethod CallShortMethodV
    CallShortMethodA CallIntMethod CallIntMethodV CallIntMethodA CallLongMethod CallLongMethodV
    CallLongMethodA CallFloatMethod CallFloatMethodV CallFloatMethodA CallDoubleMethod CallDoubleMethodV
    CallDoubleMethodA CallVoidMethod CallVoidMethodV CallVoidMethodA CallNonvirtualObjectMethod
    CallNonvirtualObjectMethodV CallNonvirtualObjectMethodA CallNonvirtualBooleanMethod CallNonvirtualBooleanMethodV
    CallNonvirtualBooleanMethodA CallNonvirtualByteMethod CallNonvirtualByteMethodV CallNonvirtualByteMethodA
    CallNonvirtualCharMethod CallNonvirtualCharMethodV CallNonvirtualCharMethodA CallNonvirtualShortMethod
    CallNonvirtualShortMethodV CallNonvirtualShortMethodA CallNonvirtualIntMethod CallNonvirtualIntMethodV
    CallNonvirtualIntMethodA CallNonvirtualLongMethod CallNonvirtualLongMethodV CallNonvirtualLongMethodA
    CallNonvirtualFloatMethod CallNonvirtualFloatMethodV CallNonvirtualFloatMethodA CallNonvirtualDoubleMethod
    CallNonvirtualDoubleMethodV CallNonvirtualDoubleMethodA CallNonvirtualVoidMethod CallNonvirtualVoidMethodV
    CallNonvirtualVoidMethodA GetFieldID GetObjectField GetBooleanField GetByteField GetCharField
    GetShortField GetIntField GetLongField GetFloatField GetDoubleField SetObjectField SetBooleanField
    SetByteField SetCharField SetShortField SetIntField SetLongField SetFloatField SetDoubleField
    GetStaticMethodID CallStaticObjectMethod CallStaticObjectMethodV CallStaticObjectMethodA CallStaticBooleanMethod
    CallStaticBooleanMethodV CallStaticBooleanMethodA CallStaticByteMethod CallStaticByteMethodV
    CallStaticByteMethodA CallStaticCharMethod CallStaticCharMethodV CallStaticCharMethodA CallStaticShortMethod
    CallStaticShortMethodV CallStaticShortMethodA CallStaticIntMethod CallStaticIntMethodV CallStaticIntMethodA
    CallStaticLongMethod CallStaticLongMethodV CallStaticLongMethodA CallStaticFloatMethod CallStaticFloatMethodV
    CallStaticFloatMethodA CallStaticDoubleMethod CallStaticDoubleMethodV CallStaticDoubleMethodA
    CallStaticVoidMethod CallStaticVoidMethodV CallStaticVoidMethodA GetStaticFieldID GetStaticObjectField
    GetStaticBooleanField GetStaticByteField GetStaticCharField GetStaticShortField GetStaticIntField
    GetStaticLongField GetStaticFloatField GetStaticDoubleField SetStaticObjectField SetStaticBooleanField
    SetStaticByteField SetStaticCharField SetStaticShortField SetStaticIntField SetStaticLongField
    SetStaticFloatField SetStaticDoubleField NewString GetStringLength GetStringChars ReleaseStringChars
    NewStringUTF GetStringUTFLength GetStringUTFChars ReleaseStringUTFChars GetArrayLength NewObjectArray
    GetObjectArrayElement SetObjectArrayElement NewBooleanArray NewByteArray NewCharArray NewShortArray
    NewIntArray NewLongArray NewFloatArray NewDoubleArray GetBooleanArrayElements GetByteArrayElements
    GetCharArrayElements GetShortArrayElements GetIntArrayElements GetLongArrayElements GetFloatArrayElements
    GetDoubleArrayElements ReleaseBooleanArrayElements ReleaseByteArrayElements ReleaseCharArrayElements
    ReleaseShortArrayElements ReleaseIntArrayElements ReleaseLongArrayElements ReleaseFloatArrayElements
    ReleaseDoubleArrayElements GetBooleanArrayRegion GetByteArrayRegion GetCharArrayRegion GetShortArrayRegion
    GetIntArrayRegion GetLongArrayRegion GetFloatArrayRegion GetDoubleArrayRegion SetBooleanArrayRegion
    SetByteArrayRegion SetCharArrayRegion SetShortArrayRegion SetIntArrayRegion SetLongArrayRegion
    SetFloatArrayRegion SetDoubleArrayRegion RegisterNatives UnregisterNatives MonitorEnter MonitorExit
    GetJavaVM GetStringRegion GetStringUTFRegion GetPrimitiveArrayCritical ReleasePrimitiveArrayCritical
    GetStringCritical ReleaseStringCritical NewWeakGlobalRef DeleteWeakGlobalRef ExceptionCheck
    NewDirectByteBuffer GetDirectBufferAddress GetDirectBufferCapacity GetObjectRefType GetModule
    IsVirtualThread
""".split()

# JavaVM (invocation interface) table.
VM_FUNCTIONS = "reserved0 reserved1 reserved2 DestroyJavaVM AttachCurrentThread DetachCurrentThread GetEnv " \
               "AttachCurrentThreadAsDaemon".split()

# JNI type letters in the order the specification lists the typed function families.
CALL_TYPES = "LZBCSIJFDV"      # Call<Type>Method: Object, Boolean, ..., Double, Void
FIELD_TYPES = "LZBCSIJFD"      # Get<Type>Field
ARRAY_TYPES = "ZBCSIJFD"       # New<Type>Array and friends
ELEMENT_SIZE = {"Z": 1, "B": 1, "C": 2, "S": 2, "I": 4, "J": 8, "F": 4, "D": 8}
JNI_VERSION = 0x00010008
JNI_ABORT = 2


def log(*parts):
    print(*parts, file=sys.stderr, flush=True)


def parse_signature(signature):
    """Splits a method descriptor into a list of argument type letters and the return letter.

    Object and array types are reduced to "L": at this level they are all handles.
    """
    args, i = [], 1
    while signature[i] != ")":
        if signature[i] == "L":
            i = signature.index(";", i)
            args.append("L")
        elif signature[i] == "[":
            while signature[i] == "[":
                i += 1
            if signature[i] == "L":
                i = signature.index(";", i)
            args.append("L")
        else:
            args.append(signature[i])
        i += 1
    ret = signature[i + 1]
    return args, ("L" if ret in "L[" else ret)


class Bridge:
    def __init__(self):
        self.se = speakeasy.Speakeasy()
        self.modules = []
        self.method_ids = {}       # id -> (class handle, name, signature, is_static)
        self.field_ids = {}        # id -> (class handle, name, signature, is_static)
        self.next_id = 0x10000
        self.pinned = {}           # emulated pointer -> (array handle, type letter, length)
        self.reported_apis = 0
        self.tables_built = False

    # ------------------------------------------------------------------ protocol

    def send(self, message):
        sys.stdout.write(json.dumps(message) + "\n")
        sys.stdout.flush()

    def callback(self, kind, **fields):
        """Asks JavaByte to do something only it can (anything involving a Java object)."""
        self.send({"callback": kind, **fields})
        line = sys.stdin.readline()
        if not line:
            raise SystemExit(0)  # JavaByte went away
        reply = json.loads(line)
        if "op" in reply:
            # JavaByte is asking for another native call while this one is still running (the
            # Java method we called back into is itself native). The CPU emulator cannot be
            # re-entered, so decline; JavaByte reports the inner call as not emulated.
            self.send({"error": "nested native call is not supported"})
            return self.callback_wait()
        return reply.get("v", 0)

    def callback_wait(self):
        line = sys.stdin.readline()
        if not line:
            raise SystemExit(0)
        return json.loads(line).get("v", 0)

    def event(self, category, action, **data):
        self.send({"event": {"category": category, "action": action, "data": data}})

    # ------------------------------------------------------------------ emulated memory helpers

    def _build_tables(self):
        """Creates the JNIEnv and JavaVM structures inside the emulated address space.

        Each function slot points at its own byte of a "trap" page filled with RET
        instructions. A code hook on that page fires when the DLL calls a JNI function; the
        handler does the work and sets the return value, and the RET then returns to the DLL.
        """
        se = self.se
        count = len(JNI_FUNCTIONS) + len(VM_FUNCTIONS)
        self.trap = se.mem_alloc(0x1000, tag="jni.trap")
        se.mem_write(self.trap, b"\xc3" * 0x1000)
        table = se.mem_alloc(8 * len(JNI_FUNCTIONS), tag="jni.functions")
        se.mem_write(table, b"".join(struct.pack("<Q", self.trap + i) for i in range(len(JNI_FUNCTIONS))))
        self.env = se.mem_alloc(8, tag="jni.env")
        se.mem_write(self.env, struct.pack("<Q", table))
        vm_table = se.mem_alloc(8 * len(VM_FUNCTIONS), tag="jni.vmfunctions")
        base = self.trap + len(JNI_FUNCTIONS)
        se.mem_write(vm_table, b"".join(struct.pack("<Q", base + i) for i in range(len(VM_FUNCTIONS))))
        self.vm = se.mem_alloc(8, tag="jni.vm")
        se.mem_write(self.vm, struct.pack("<Q", vm_table))
        se.add_code_hook(self._on_trap, begin=self.trap, end=self.trap + count)

    def arg(self, index):
        """The index-th integer argument of the JNI call in progress (Windows x64 convention)."""
        if index < 4:
            return self.se.reg_read(("rcx", "rdx", "r8", "r9")[index])
        # At function entry RSP points at the return address; stack arguments follow the
        # 32-byte shadow area.
        return struct.unpack("<Q", self.se.mem_read(self.se.reg_read("rsp") + 8 + 8 * index, 8))[0]

    def c_string(self, address):
        out = bytearray()
        while address and len(out) < 65536:
            chunk = self.se.mem_read(address + len(out), 64)
            end = chunk.find(b"\0")
            if end >= 0:
                out += chunk[:end]
                break
            out += chunk
        return bytes(out)

    def alloc_bytes(self, data):
        address = self.se.mem_alloc(max(len(data), 1), tag="jni.data")
        self.se.mem_write(address, bytes(data))
        return address

    def new_id(self, table, entry):
        self.next_id += 8
        table[self.next_id] = entry
        return self.next_id

    # ------------------------------------------------------------------ argument marshalling

    @staticmethod
    def to_java(raw, letter):
        """Converts a raw 64-bit argument slot into a JSON value for JavaByte."""
        if letter in "ZBCSI":
            value = raw & 0xFFFFFFFF
            return value - (1 << 32) if value & 0x80000000 else value
        if letter == "J":
            return raw - (1 << 64) if raw & (1 << 63) else raw
        if letter == "D":
            return struct.unpack("<d", struct.pack("<Q", raw))[0]
        if letter == "F":
            return struct.unpack("<f", struct.pack("<I", raw & 0xFFFFFFFF))[0]
        return raw  # an object handle

    def read_call_args(self, letters, form, first):
        """Reads a Java method's arguments from a Call<Type>Method{,V,A} invocation.

        form 0: C varargs, starting at argument `first`. Floats are promoted to double.
        form 1: a va_list, which on Windows x64 is a pointer to consecutive 8-byte slots.
        form 2: a pointer to an array of 8-byte jvalue unions.
        """
        values = []
        for i, letter in enumerate(letters):
            if form == 0:
                raw = self.arg(first + i)
                if letter == "F":
                    letter_in = "D"
                else:
                    letter_in = letter
            else:
                raw = struct.unpack("<Q", self.se.mem_read(self.arg(first) + 8 * i, 8))[0]
                letter_in = "D" if (letter == "F" and form == 1) else letter
            values.append({"t": letter, "v": self.to_java(raw, letter_in)})
        return values

    def set_result(self, value, letter="J"):
        """Places a JNI function's return value where the DLL expects it."""
        if letter in "FD":
            packed = struct.pack("<d" if letter == "D" else "<f", float(value or 0))
            try:
                self.se.reg_write("xmm0", int.from_bytes(packed.ljust(16, b"\0"), "little"))
            except Exception as error:  # the register may be unavailable in some engine builds
                log("cannot set xmm0:", error)
            return
        self.se.reg_write("rax", int(value or 0) & 0xFFFFFFFFFFFFFFFF)

    # ------------------------------------------------------------------ JNI dispatch

    def _on_trap(self, emu, address, size, ctx):
        index = address - self.trap
        try:
            if index >= len(JNI_FUNCTIONS):
                self._vm_function(VM_FUNCTIONS[index - len(JNI_FUNCTIONS)])
            else:
                self._jni_function(index, JNI_FUNCTIONS[index])
        except SystemExit:
            raise
        except Exception as error:
            # A failure here must not take the helper down mid-call: report and return zero.
            log("JNI handler failed:", JNI_FUNCTIONS[index] if index < len(JNI_FUNCTIONS) else index, repr(error))
            self.event("gap", "jni-handler-failed", function=str(index), error=repr(error))
            self.set_result(0)
        return True

    def _vm_function(self, name):
        if name in ("GetEnv", "AttachCurrentThread", "AttachCurrentThreadAsDaemon"):
            self.se.mem_write(self.arg(1), struct.pack("<Q", self.env))
        self.set_result(0)

    def _jni_function(self, index, name):
        a = self.arg
        cb = self.callback

        # ---- typed families, identified by slot range
        if 34 <= index <= 63:        # Call<Type>Method{,V,A}(env, obj, methodID, ...)
            return self._call_method(index - 34, a(1), a(2), first=3, static=False, nonvirtual=False)
        if 64 <= index <= 93:        # CallNonvirtual<Type>Method{,V,A}(env, obj, class, methodID, ...)
            return self._call_method(index - 64, a(1), a(3), first=4, static=False, nonvirtual=True)
        if 114 <= index <= 143:      # CallStatic<Type>Method{,V,A}(env, class, methodID, ...)
            return self._call_method(index - 114, a(1), a(2), first=3, static=True, nonvirtual=False)
        if 95 <= index <= 103 or 145 <= index <= 153:      # Get<Type>Field / GetStatic<Type>Field
            static = index >= 145
            letter = FIELD_TYPES[index - (145 if static else 95)]
            cls, field, signature, _ = self.field_ids[a(2)]
            value = cb("getField", static=static, cls=cls, obj=0 if static else a(1), name=field, sig=signature)
            return self.set_result(value, letter)
        if 104 <= index <= 112 or 154 <= index <= 162:     # Set<Type>Field / SetStatic<Type>Field
            static = index >= 154
            letter = FIELD_TYPES[index - (154 if static else 104)]
            cls, field, signature, _ = self.field_ids[a(2)]
            cb("setField", static=static, cls=cls, obj=0 if static else a(1), name=field, sig=signature,
               v=self.to_java(a(3), letter))
            return self.set_result(0)
        if 175 <= index <= 182:      # New<Type>Array(env, length)
            return self.set_result(cb("newArray", type=ARRAY_TYPES[index - 175], length=a(1) & 0xFFFFFFFF))
        if 183 <= index <= 190:      # Get<Type>ArrayElements(env, array, isCopy)
            return self._pin_array(a(1), a(2))
        if 191 <= index <= 198:      # Release<Type>ArrayElements(env, array, elements, mode)
            return self._unpin_array(a(2), a(3) & 0xFFFFFFFF)
        if 199 <= index <= 206:      # Get<Type>ArrayRegion(env, array, start, length, buffer)
            letter = ARRAY_TYPES[index - 199]
            data = base64.b64decode(cb("arrayGet", h=a(1), start=a(2) & 0xFFFFFFFF, length=a(3) & 0xFFFFFFFF))
            self.se.mem_write(a(4), data)
            return self.set_result(0)
        if 207 <= index <= 214:      # Set<Type>ArrayRegion(env, array, start, length, buffer)
            letter = ARRAY_TYPES[index - 207]
            length = a(3) & 0xFFFFFFFF
            data = self.se.mem_read(a(4), length * ELEMENT_SIZE[letter]) if length else b""
            cb("arraySet", h=a(1), start=a(2) & 0xFFFFFFFF, data=base64.b64encode(data).decode())
            return self.set_result(0)

        # ---- individual functions
        if name == "GetVersion":
            return self.set_result(JNI_VERSION)
        if name == "FindClass":
            return self.set_result(cb("findClass", name=self.c_string(a(1)).decode("utf-8", "replace")))
        if name in ("GetMethodID", "GetStaticMethodID"):
            static = name == "GetStaticMethodID"
            method = self.c_string(a(2)).decode("utf-8", "replace")
            signature = self.c_string(a(3)).decode("utf-8", "replace")
            found = cb("hasMethod", cls=a(1), name=method, sig=signature, static=static)
            return self.set_result(self.new_id(self.method_ids, (a(1), method, signature, static)) if found else 0)
        if name in ("GetFieldID", "GetStaticFieldID"):
            static = name == "GetStaticFieldID"
            field = self.c_string(a(2)).decode("utf-8", "replace")
            signature = self.c_string(a(3)).decode("utf-8", "replace")
            found = cb("hasField", cls=a(1), name=field, sig=signature, static=static)
            return self.set_result(self.new_id(self.field_ids, (a(1), field, signature, static)) if found else 0)
        if name in ("NewObject", "NewObjectV", "NewObjectA"):
            cls, _, signature, _ = self.method_ids[a(2)]
            letters, _ = parse_signature(signature)
            form = ("NewObject", "NewObjectV", "NewObjectA").index(name)
            return self.set_result(cb("newObject", cls=a(1), sig=signature, args=self.read_call_args(letters, form, 3)))
        if name == "GetObjectClass":
            return self.set_result(cb("getObjectClass", h=a(1)))
        if name == "GetSuperclass":
            return self.set_result(cb("getSuperclass", cls=a(1)))
        if name == "IsInstanceOf":
            return self.set_result(cb("isInstanceOf", h=a(1), cls=a(2)))
        if name == "IsAssignableFrom":
            return self.set_result(cb("isAssignableFrom", sub=a(1), sup=a(2)))
        if name == "IsSameObject":
            return self.set_result(1 if a(1) == a(2) else 0)

        if name == "NewStringUTF":
            return self.set_result(cb("newString", data=base64.b64encode(self.c_string(a(1))).decode(), encoding="utf-8"))
        if name == "NewString":
            raw = self.se.mem_read(a(1), 2 * (a(2) & 0xFFFFFFFF)) if a(2) else b""
            return self.set_result(cb("newString", data=base64.b64encode(raw).decode(), encoding="utf-16le"))
        if name in ("GetStringUTFChars", "GetStringChars", "GetStringCritical"):
            encoding = "utf-8" if name == "GetStringUTFChars" else "utf-16le"
            data = base64.b64decode(cb("stringBytes", h=a(1), encoding=encoding))
            if a(2):
                self.se.mem_write(a(2), b"\1")  # *isCopy = JNI_TRUE
            return self.set_result(self.alloc_bytes(data + b"\0\0"))
        if name in ("GetStringUTFLength", "GetStringLength"):
            encoding = "utf-8" if name == "GetStringUTFLength" else "utf-16le"
            data = base64.b64decode(cb("stringBytes", h=a(1), encoding=encoding))
            return self.set_result(len(data) // (1 if encoding == "utf-8" else 2))
        if name in ("GetStringRegion", "GetStringUTFRegion"):
            text = base64.b64decode(cb("stringBytes", h=a(1), encoding="utf-16le")).decode("utf-16le")
            part = text[a(2) & 0xFFFFFFFF:(a(2) + a(3)) & 0xFFFFFFFF]
            encoded = part.encode("utf-16le") if name == "GetStringRegion" else part.encode("utf-8") + b"\0"
            self.se.mem_write(a(4), encoded)
            return self.set_result(0)

        if name == "GetArrayLength":
            return self.set_result(cb("arrayInfo", h=a(1))["length"])
        if name == "NewObjectArray":
            return self.set_result(cb("newArray", type="L", length=a(1) & 0xFFFFFFFF, cls=a(2), init=a(3)))
        if name == "GetObjectArrayElement":
            return self.set_result(cb("objectArrayGet", h=a(1), index=a(2) & 0xFFFFFFFF))
        if name == "SetObjectArrayElement":
            cb("objectArraySet", h=a(1), index=a(2) & 0xFFFFFFFF, v=a(3))
            return self.set_result(0)
        if name == "GetPrimitiveArrayCritical":
            return self._pin_array(a(1), a(2))
        if name == "ReleasePrimitiveArrayCritical":
            return self._unpin_array(a(2), a(3) & 0xFFFFFFFF)

        if name == "RegisterNatives":
            methods = []
            for i in range(a(3) & 0xFFFFFFFF):
                name_ptr, sig_ptr, function = struct.unpack("<QQQ", self.se.mem_read(a(2) + 24 * i, 24))
                methods.append({"name": self.c_string(name_ptr).decode("utf-8", "replace"),
                                "sig": self.c_string(sig_ptr).decode("utf-8", "replace"), "address": function})
            cb("registerNatives", cls=a(1), methods=methods)
            return self.set_result(0)
        if name == "GetJavaVM":
            self.se.mem_write(a(1), struct.pack("<Q", self.vm))
            return self.set_result(0)

        if name == "ThrowNew":
            cb("throwNew", cls=a(1), message=self.c_string(a(2)).decode("utf-8", "replace"))
            return self.set_result(0)
        if name == "Throw":
            cb("throw", h=a(1))
            return self.set_result(0)
        if name in ("ExceptionOccurred", "ExceptionCheck"):
            return self.set_result(cb("exception", query=name))
        if name in ("ExceptionClear", "ExceptionDescribe"):
            cb("exception", query=name)
            return self.set_result(0)

        # References are plain handles owned by JavaByte, so reference management is a no-op.
        if name in ("NewGlobalRef", "NewLocalRef", "NewWeakGlobalRef", "PopLocalFrame"):
            return self.set_result(a(1))
        if name in ("DeleteGlobalRef", "DeleteLocalRef", "DeleteWeakGlobalRef", "PushLocalFrame", "EnsureLocalCapacity",
                    "MonitorEnter", "MonitorExit", "UnregisterNatives", "ReleaseStringUTFChars", "ReleaseStringChars",
                    "ReleaseStringCritical"):
            return self.set_result(0)

        self.event("gap", "unsupported-jni-function", function=name)
        self.set_result(0)

    def _call_method(self, offset, target, method_id, first, static, nonvirtual):
        cls, name, signature, _ = self.method_ids[method_id]
        letters, ret = parse_signature(signature)
        result = self.callback("call", static=static, nonvirtual=nonvirtual, cls=cls, obj=0 if static else target,
                               name=name, sig=signature, args=self.read_call_args(letters, offset % 3, first))
        self.set_result(result, ret)

    def _pin_array(self, handle, is_copy):
        info = self.callback("arrayInfo", h=handle)
        data = base64.b64decode(self.callback("arrayGet", h=handle, start=0, length=info["length"]))
        address = self.alloc_bytes(data)
        self.pinned[address] = (handle, info["type"], info["length"])
        if is_copy:
            self.se.mem_write(is_copy, b"\1")
        self.set_result(address)

    def _unpin_array(self, address, mode):
        handle, letter, length = self.pinned.get(address, (0, "B", 0))
        if handle and mode != JNI_ABORT and length:
            data = self.se.mem_read(address, length * ELEMENT_SIZE[letter])
            self.callback("arraySet", h=handle, start=0, data=base64.b64encode(data).decode())
        if mode != 1:  # JNI_COMMIT keeps the buffer alive
            self.pinned.pop(address, None)
        self.set_result(0)

    # ------------------------------------------------------------------ requests

    def report_api_calls(self):
        """Forwards the Windows API calls the emulated code has made since the last report."""
        apis = []
        for entry in self.se.get_report().get("entry_points", []):
            apis.extend(entry.get("apis", []))
        for api in apis[self.reported_apis:]:
            self.event("native", "api", api=api.get("api_name"), args=[str(x) for x in api.get("args", [])],
                       result=str(api.get("ret_val")))
        self.reported_apis = len(apis)

    def load(self, path):
        module = self.se.load_module(path)
        if not self.tables_built:
            # Speakeasy creates its address space when the first module is loaded, so the JNI
            # structures can only be placed in it from this point on.
            self._build_tables()
            self.tables_built = True
        self.modules.append(module)
        self.se.run_module(module, all_entrypoints=False)  # runs DllMain only
        exports = {export.name: export.address for export in module.get_exports() if export.name}
        if "JNI_OnLoad" in exports:
            self.se.call(exports["JNI_OnLoad"], [self.vm, 0])
        self.report_api_calls()
        return {"exports": exports}

    def call(self, request):
        params = [self.env, request["self"]]
        for position, argument in enumerate(request["args"], start=2):
            letter, value = argument["t"], argument["v"]
            if letter in "FD":
                packed = struct.pack("<d" if letter == "D" else "<f", value)
                raw = int.from_bytes(packed.ljust(8, b"\0"), "little")
                if position < 4:
                    # Windows x64 passes the first four arguments by position; a floating
                    # point one travels in the XMM register of the same position.
                    try:
                        self.se.reg_write("xmm%d" % position, raw)
                    except Exception as error:
                        log("cannot set xmm register:", error)
                params.append(raw)
            else:
                params.append(int(value) & 0xFFFFFFFFFFFFFFFF)
        self.se.call(request["address"], params)
        self.report_api_calls()
        ret = request["ret"]
        if ret in "FD":
            try:
                raw = self.se.reg_read("xmm0")
                data = (raw & ((1 << 64) - 1)).to_bytes(8, "little")
                return {"v": struct.unpack("<d", data)[0] if ret == "D" else struct.unpack("<f", data[:4])[0]}
            except Exception as error:
                log("cannot read xmm0:", error)
                return {"v": 0.0}
        return {"v": self.to_java(self.se.reg_read("rax"), ret if ret != "V" else "J")}

    def serve(self):
        for line in sys.stdin:
            request = json.loads(line)
            try:
                if request["op"] == "shutdown":
                    return
                if request["op"] == "load":
                    self.send({"result": self.load(request["path"])})
                elif request["op"] == "call":
                    self.send({"result": self.call(request)})
                else:
                    self.send({"error": "unknown request " + str(request.get("op"))})
            except SystemExit:
                return
            except Exception as error:
                log("request failed:", repr(error))
                self.send({"error": repr(error)})


if __name__ == "__main__":
    Bridge().serve()
