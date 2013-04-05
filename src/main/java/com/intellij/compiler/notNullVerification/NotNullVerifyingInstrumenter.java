/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intellij.compiler.notNullVerification;

import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

/**
 * @author ven
 * @author Vladislav.Rassokhin
 */
public class NotNullVerifyingInstrumenter extends ClassVisitor implements Opcodes {
  private static final String NOT_NULL_CLASS_NAME = "org/jetbrains/annotations/NotNull";
  private static final String NOT_NULL_TYPE = "L" + NOT_NULL_CLASS_NAME + ";";
  private static final String SYNTHETIC_CLASS_NAME = "java/lang/Synthetic";
  private static final String SYNTHETIC_TYPE = "L" + SYNTHETIC_CLASS_NAME + ";";
  private static final String IAE_CLASS_NAME = "java/lang/IllegalArgumentException";
  private static final String ISE_CLASS_NAME = "java/lang/IllegalStateException";
  private static final String STRING_CLASS_NAME = "java/lang/String";
  private static final String CONSTRUCTOR_NAME = "<init>";
  private static final String EXCEPTION_INIT_SIGNATURE = "(L" + STRING_CLASS_NAME + ";)V";

  private static final String NULL_ARG_MESSAGE = "Argument %d for @NotNull parameter of %s.%s must not be null";
  private static final String NULL_RESULT_MESSAGE = "@NotNull method %s.%s must not return null";

  private String myClassName;
  private boolean myIsModification = false;

  public NotNullVerifyingInstrumenter(final ClassVisitor classVisitor) {
    super(Opcodes.ASM4, classVisitor);
  }

  public boolean isModification() {
    return myIsModification;
  }

  @Override
  public void visit(final int version, final int access, final String name, final String signature, final String superName, final String[] interfaces) {
    super.visit(version, access, name, signature, superName, interfaces);
    myClassName = name;
  }

  @Override
  public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions) {
    final Type[] args = Type.getArgumentTypes(desc);
    final Type returnType = Type.getReturnType(desc);
    final MethodVisitor v = cv.visitMethod(access, name, desc, signature, exceptions);
    return new MethodVisitor(Opcodes.ASM4, v) {

      private final List<Integer> myNotNullParams = new ArrayList<Integer>();
      private int mySyntheticCount = 0;
      private boolean myIsNotNull = false;
      private Label myStartGeneratedCodeLabel;

      @Override
      public AnnotationVisitor visitParameterAnnotation(final int parameter, final String anno, final boolean visible) {
        final AnnotationVisitor av = mv.visitParameterAnnotation(parameter, anno, visible);
        if (isReferenceType(args[parameter]) && anno.equals(NOT_NULL_TYPE)) {
          myNotNullParams.add(parameter);
        } else if (anno.equals(SYNTHETIC_TYPE)) {
          // see http://forge.ow2.org/tracker/?aid=307392&group_id=23&atid=100023&func=detail
          mySyntheticCount++;
        }
        return av;
      }

      @Override
      public AnnotationVisitor visitAnnotation(String anno, boolean isRuntime) {
        final AnnotationVisitor av = mv.visitAnnotation(anno, isRuntime);
        if (isReferenceType(returnType) && anno.equals(NOT_NULL_TYPE)) {
          myIsNotNull = true;
        }

        return av;
      }

      @Override
      public void visitCode() {
        if (myNotNullParams.size() > 0) {
          myStartGeneratedCodeLabel = new Label();
          mv.visitLabel(myStartGeneratedCodeLabel);
        }
        for (Integer param : myNotNullParams) {
          int var = ((access & ACC_STATIC) == 0) ? 1 : 0;
          for (int i = 0; i < param; ++i) {
            var += args[i].getSize();
          }
          mv.visitVarInsn(ALOAD, var);

          Label end = new Label();
          mv.visitJumpInsn(IFNONNULL, end);

          generateThrow(IAE_CLASS_NAME, String.format(NULL_ARG_MESSAGE, param - mySyntheticCount, myClassName, name), end);
        }
      }

      @Override
      public void visitLocalVariable(final String name, final String desc, final String signature, final Label start, final Label end, final int index) {
        final boolean isStatic = (access & ACC_STATIC) != 0;
        final boolean isParameter = isStatic ? index < args.length : index <= args.length;
        final Label label = (isParameter && myStartGeneratedCodeLabel != null) ? myStartGeneratedCodeLabel : start;
        mv.visitLocalVariable(name, desc, signature, label, end, index);
      }

      @Override
      public void visitInsn(int opcode) {
        if (opcode == ARETURN) {
          if (myIsNotNull) {
            mv.visitInsn(DUP);
            final Label skipLabel = new Label();
            mv.visitJumpInsn(IFNONNULL, skipLabel);
            generateThrow(ISE_CLASS_NAME, String.format(NULL_RESULT_MESSAGE, myClassName, name), skipLabel);
          }
        }

        mv.visitInsn(opcode);
      }

      private void generateThrow(final String exceptionClass, final String descr, final Label end) {
        mv.visitTypeInsn(NEW, exceptionClass);
        mv.visitInsn(DUP);
        mv.visitLdcInsn(descr);
        mv.visitMethodInsn(INVOKESPECIAL, exceptionClass, CONSTRUCTOR_NAME, EXCEPTION_INIT_SIGNATURE);
        mv.visitInsn(ATHROW);
        mv.visitLabel(end);

        myIsModification = true;
      }

      @Override
      public void visitMaxs(final int maxStack, final int maxLocals) {
        try {
          super.visitMaxs(maxStack, maxLocals);
        } catch (ArrayIndexOutOfBoundsException e) {
          throw new ArrayIndexOutOfBoundsException("Maximums processing failed for " + myClassName + "." + name + ": " + e.getMessage());
        }
      }
    };
  }

  private static boolean isReferenceType(final Type type) {
    return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY;
  }
}
