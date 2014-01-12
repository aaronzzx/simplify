package simplify.exec.instruction;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import org.jf.dexlib2.AccessFlags;
import org.jf.dexlib2.iface.instruction.Instruction;
import org.jf.dexlib2.iface.instruction.ReferenceInstruction;
import org.jf.dexlib2.iface.instruction.formats.Instruction35c;
import org.jf.dexlib2.iface.instruction.formats.Instruction3rc;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.util.ReferenceUtil;
import org.jf.dexlib2.writer.builder.BuilderClassDef;
import org.jf.dexlib2.writer.builder.BuilderMethod;

import simplify.Simplifier;
import simplify.emulate.MethodEmulator;
import simplify.exec.MethodExecutionContext;
import simplify.exec.RegisterStore;
import simplify.exec.UnknownValue;

public class InvokeInstruction {
    private static final Logger log = Logger.getLogger(Simplifier.class.getSimpleName());

    public static void execute(MethodExecutionContext ectx, Instruction instruction, int index,
                    List<BuilderClassDef> classes) {
        System.out.println("invoke " + instruction.getOpcode());

        MethodReference method = getMethodReference(instruction);
        // Build called method context even before we know it's emulated because we'll
        // need to know types later to mark them as unknown if it's not emulated.
        MethodExecutionContext calledMethodContext = buildCalledMethodContext(ectx, instruction, method, index);

        String methodDescriptor = ReferenceUtil.getMethodDescriptor(method);
        if (MethodEmulator.canEmulate(methodDescriptor)) {
            MethodEmulator.emulate(calledMethodContext, methodDescriptor);

            if (!method.getReturnType().equals("V")) {
                ectx.setResultRegister(calledMethodContext.getReturnRegister());
            }
        } else {
            List<BuilderMethod> methods = new ArrayList<BuilderMethod>();
            for (BuilderClassDef classDef : classes) {
                methods.addAll(classDef.getMethods());
            }
            BuilderMethod targetMethod = getMethodFromDescriptor(methods, methodDescriptor);
            if (targetMethod != null) {
                // TODO: Execute method if in methods.
                log.warning("Found " + methodDescriptor + " but holding off on executing it.");
            }
            // else (assume always else, for now)

            // If the method isn't emulated or implemented in target, we don't know the return value.
            if (!method.getReturnType().equals("V")) {
                ectx.setResultRegister(new RegisterStore("?", new UnknownValue()));
            }

            // Also, any non-final classes passed as non-final parameters could have changed.
            int paramStart = calledMethodContext.getParameterStart();
            for (int i = paramStart; i < calledMethodContext.getRegisterCount(); i++) {
                RegisterStore rs = calledMethodContext.getRegister(i, 0);
                if (!isImmutableClass(classes, rs.getType())) {
                    log.finer("r" + i + " (" + rs.getType() + ") is mutable and passed as param, marking as unknown");
                    ectx.addRegister(i, "?", new UnknownValue(), index);
                } else {
                    log.finer("r" + i + " (" + rs.getType() + ") is immutable and passed as param, retaining value");
                }
            }
        }
    }

    private static int[] getInvokeRegisters(Instruction instruction) {
        int[] result;
        if (instruction.getOpcode().name.endsWith("range")) {
            Instruction3rc instr = (Instruction3rc) instruction;
            result = new int[instr.getRegisterCount()];

            int start = instr.getStartRegister();
            for (int i = 0; i < result.length; i++) {
                result[i] = start + i;
            }
        } else {
            Instruction35c instr = (Instruction35c) instruction;
            result = new int[instr.getRegisterCount()];

            switch (result.length) {
            case 5:
                result[4] = instr.getRegisterG();
            case 4:
                result[3] = instr.getRegisterF();
            case 3:
                result[2] = instr.getRegisterE();
            case 2:
                result[1] = instr.getRegisterD();
            case 1:
                result[0] = instr.getRegisterC();
            }
        }

        return result;
    }

    private static MethodExecutionContext buildCalledMethodContext(MethodExecutionContext ectx,
                    Instruction instruction, MethodReference method, int index) {
        int[] invokeRegisters = getInvokeRegisters(instruction);
        MethodExecutionContext calledMethodContext = new MethodExecutionContext(invokeRegisters.length,
                        invokeRegisters.length, ectx.getRemaingCallDepth() - 1);
        boolean isStatic = instruction.getOpcode().name.startsWith("invoke-static");
        if (!isStatic) {
            RegisterStore rs = ectx.getRegister(invokeRegisters[0], index);
            calledMethodContext.addParameterRegister(0, rs);
            invokeRegisters = Arrays.copyOfRange(invokeRegisters, 1, invokeRegisters.length);
        }

        List<? extends CharSequence> parameterTypes = method.getParameterTypes();
        for (int i = 0; i < invokeRegisters.length; i++) {
            Object value = ectx.getRegisterValue(invokeRegisters[i], index);
            if (!isStatic) {
                calledMethodContext.addParameterRegister(i + 1, parameterTypes.get(i).toString(), value);
            } else {
                calledMethodContext.addParameterRegister(i, parameterTypes.get(i).toString(), value);
            }
        }

        return calledMethodContext;
    }

    private static MethodReference getMethodReference(Instruction instruction) {
        return (MethodReference) ((ReferenceInstruction) instruction).getReference();
    }

    private static BuilderMethod getMethodFromDescriptor(List<BuilderMethod> methods, String methodDescriptor) {
        for (BuilderMethod method : methods) {
            String md = ReferenceUtil.getMethodDescriptor(method);
            if (md.equals(methodDescriptor)) {
                return method;
            }
        }

        return null;
    }

    private static BuilderClassDef getClassFromType(List<BuilderClassDef> classes, String type) {
        for (BuilderClassDef classDef : classes) {
            if (classDef.getType().equals(type)) {
                return classDef;
            }
        }

        return null;
    }

    private static boolean isImmutableClass(List<BuilderClassDef> classes, String type) {
        if (type.length() == 1) {
            // Primitive type, e.g. I, Z, B, etc.
            return true;
        }

        BuilderClassDef classDef = getClassFromType(classes, type);
        if (classDef != null) {
            return (classDef.getAccessFlags() & AccessFlags.FINAL.getValue()) != 0;
        }

        String javaName = type.substring(1, type.length() - 1).replaceAll("/", ".");
        try {
            Class clazz = Class.forName(javaName);

            return (clazz.getModifiers() & Modifier.FINAL) != 0;
        } catch (ClassNotFoundException e) {
        }

        return false;
    }
}